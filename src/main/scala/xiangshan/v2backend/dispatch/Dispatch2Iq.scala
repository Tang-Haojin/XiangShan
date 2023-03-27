package xiangshan.v2backend.dispatch

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}
import utility.SelectOne
import utils._
import xiangshan._
import xiangshan.backend.rename.BusyTableReadIO
import xiangshan.mem.LsqEnqIO
import xiangshan.v2backend.Bundles.DynInst
import xiangshan.v2backend._

import scala.collection._

class Dispatch2Iq(val schdBlockParams : SchdBlockParams)(implicit p: Parameters) extends LazyModule with HasXSParameter {
  val issueBlockParams = schdBlockParams.issueBlockParams

  val numIn = schdBlockParams.numUopIn
  require(issueBlockParams.size > 0 && issueBlockParams.forall(_.numEnq == issueBlockParams.head.numEnq), "issueBlock is null or the enq size of all issueBlock not be the same all\n")
  val numOut = issueBlockParams.head.numEnq
  val numIntSrc = issueBlockParams.map(_.exuBlockParams.map(_.numIntSrc).max)
  val numIntStateRead = numIntSrc.max * numIn

  val numFpSrc = issueBlockParams.map(_.exuBlockParams.map(_.numFpSrc).max)
  val numFpStateRead = numFpSrc.max * numIn

  val isMem = schdBlockParams.schdType == MemScheduler()

  lazy val module = schdBlockParams.schdType match {
    case IntScheduler() => new Dispatch2IqArithImp(this)(p, schdBlockParams)
    case MemScheduler() => new Dispatch2IqMemImp(this)(p, schdBlockParams)
    case VfScheduler() => new Dispatch2IqArithImp(this)(p, schdBlockParams)
    case _ => null
  }
}

abstract class Dispatch2IqImp(override val wrapper: Dispatch2Iq)(implicit p: Parameters, params: SchdBlockParams)
  extends LazyModuleImp(wrapper) with HasXSParameter {

  val numIntSrc = wrapper.numIntSrc.max
  val numIntStateRead = wrapper.numIntStateRead
  val numFpStateRead = wrapper.numFpStateRead
  val numIssueBlock = wrapper.issueBlockParams.size

  val io = IO(new Bundle() {
    val redirect = Flipped(ValidIO(new Redirect))
    val in = Flipped(Vec(wrapper.numIn, DecoupledIO(new DynInst)))
    val readIntState = if (numIntStateRead > 0) Some(Vec(numIntStateRead, Flipped(new BusyTableReadIO))) else None
    val readFpState = if (numFpStateRead > 0) Some(Vec(numFpStateRead, Flipped(new BusyTableReadIO))) else None
    val out = Vec(wrapper.issueBlockParams.count(_.StdCnt == 0), Vec(wrapper.numOut, DecoupledIO(new DynInst)))
    val enqLsqIO = if (wrapper.isMem) Some(Flipped(new LsqEnqIO)) else None
  })


  /**
    *
    * @param portFuSets portFuSet(i): the ith port can accept the set including [[FuType]]
    * @return set of the [[FuType]] can deq by port num
    */
  def getFuDeqMap[T](portFuSets: Seq[Set[T]]): Map[T, Seq[Int]] = {
    val res: mutable.Map[T, Seq[Int]] = mutable.Map()
    for ((set, i) <- portFuSets.zipWithIndex) {
      for (fuType <- set) {
        if (res.contains(fuType)) {
          res(fuType) :+= i
        } else {
          res += (fuType -> Seq(i))
        }
      }
    }
    res.toMap
  }

  def mergeFuDeqMap[T](map: Map[T, Seq[Int]]) = {
    val res: mutable.Map[Seq[Int], Seq[T]] = mutable.Map()
    for ((k, v) <- map) {
      if (res.contains(v)) {
        res(v) :+= k
      } else {
        res += (v -> Seq(k))
      }
    }
    res.map(x => (x._2, x._1))
  }

  def expendFuDeqMap[T](map: Map[Seq[T], Seq[Int]], numEnqs: Seq[Int]) = {
    val res: mutable.Map[Seq[T], Seq[Int]] = mutable.Map()
    val portSum: Seq[Int] = numEnqs.indices.map(x => numEnqs.slice(0, x).sum)
    for ((fuType, iqIdxSeq) <- map) {
      val portIdxSeq = iqIdxSeq.flatMap(x => Seq.range(portSum(x), portSum(x) + numEnqs(x)))
      res += (fuType -> portIdxSeq)
    }
    res
  }

  def canAccept(acceptVec: Seq[Int], fuType: UInt): Bool = {
    (acceptVec.reduce(_ | _).U & fuType).orR
  }

  def canAccept(acceptVec: Seq[Seq[Int]], fuType: UInt): Vec[Bool] = {
    VecInit(acceptVec.map(x => canAccept(x, fuType)))
  }

  def filterCanAccept(fuConfigs: Seq[FuConfig], fuType: UInt, canAcceptAlu: Boolean): Bool = {
    if(canAcceptAlu) {
      Cat(fuConfigs.map(_.fuType.U === fuType)).orR
    }
    else{
      Mux(fuType === FuType.alu.U, false.B, Cat(fuConfigs.map(_.fuType.U === fuType)).orR)
    }
  }
}

class Dispatch2IqArithImp(override val wrapper: Dispatch2Iq)(implicit p: Parameters, params: SchdBlockParams)
  extends Dispatch2IqImp(wrapper)
    with HasXSParameter {

  val portFuSets = params.issueBlockParams.map(_.exuBlockParams.flatMap(_.fuConfigs).map(_.name).toSet)
  println(s"portFuSets: $portFuSets")
  val fuDeqMap = getFuDeqMap(portFuSets)
  println(s"fuDeqMap: $fuDeqMap")
  val mergedFuDeqMap = mergeFuDeqMap(fuDeqMap)
  println(s"mergedFuDeqMap: $mergedFuDeqMap")
  val expendedFuDeqMap = expendFuDeqMap(mergedFuDeqMap, params.issueBlockParams.map(_.numEnq))
  println(s"expendedFuDeqMap: $expendedFuDeqMap")

  // sort by count of port. Port less, priority higher.
  val finalFuDeqMap = expendedFuDeqMap.toSeq.sortBy(_._2.length)

  val issuePortFuType: Seq[Seq[Int]] = params.issueBlockParams.map(_.getFuCfgs.map(_.fuType))

  val uopsIn = Wire(Vec(wrapper.numIn, DecoupledIO(new DynInst)))

  val numOutPorts = io.out.map(_.size).sum
  val numInPorts = io.in.size

  val canAcceptMatrix = Wire(Vec(numOutPorts, Vec(numInPorts, Bool())))

  for (inIdx <- 0 until numInPorts) {
    var outIdx = 0
    for (iqIdx <- io.out.indices) {
      for (portIdx <- io.out(iqIdx).indices) {
        canAcceptMatrix(outIdx)(inIdx) := canAccept(issuePortFuType(iqIdx), uopsIn(inIdx).bits.fuType)
        outIdx += 1
      }
    }
  }


  val outReadyMatrix = Wire(Vec(io.out.size, Vec(numInPorts, Bool())))
  outReadyMatrix.foreach(_.foreach(_ := false.B))

  uopsIn <> io.in
  uopsIn.foreach(_.ready := false.B)

  for ((outs, iqIdx) <- io.out.zipWithIndex) {

    val startIdx = io.out.take(iqIdx).map(_.size).sum
    val canAccept = canAcceptMatrix(startIdx).zip(io.in).map{ case (canAccept, in) => canAccept && in.valid}

    val select = SelectOne("naive", canAccept, outs.size)
    for (j <- 0 until outs.size) {
      val (selectValid, selectIdxOH) = select.getNthOH(j + 1)
      // 1 in uop can only route to one out port
      outs(j).valid := selectValid
      outs(j).bits := Mux1H(selectIdxOH, uopsIn.map(_.bits))

      outReadyMatrix(iqIdx).zip(selectIdxOH).foreach { case (inReady, v) =>
        when(v) {
          inReady := outs(j).ready
        }
      }
    }
  }

  uopsIn.zipWithIndex.foreach{ case (uopIn, idx) => uopIn.ready := outReadyMatrix.map(_(idx)).reduce(_ | _) }

  // We always read physical register states when in gives the instructions.
  // This usually brings better timing.
  if (io.readIntState.isDefined) {
    val reqPsrc = uopsIn.flatMap(in => in.bits.psrc.take(numIntSrc))
    require(io.readIntState.get.size >= reqPsrc.size, s"io.readIntState.get.size: ${io.readIntState.get.size}, psrc size: ${reqPsrc}")
    io.readIntState.get.map(_.req).zip(reqPsrc).foreach(x => x._1 := x._2)
  }


  // srcState is read from outside and connected directly
  if (io.readIntState.isDefined) {
    val intSrcStateVec = uopsIn.flatMap(_.bits.srcState.take(numIntSrc))
    io.readIntState.get.map(_.resp).zip(intSrcStateVec).foreach(x => x._2 := x._1)
  }


  XSPerfAccumulate("in_valid", PopCount(io.in.map(_.valid)))
  XSPerfAccumulate("in_fire", PopCount(io.in.map(_.fire)))
//  XSPerfAccumulate("out_valid", PopCount(io.out.map(_.valid)))
//  XSPerfAccumulate("out_fire", PopCount(io.out.map(_.fire)))
}

/**
  *
  * @param numIn
  * @param dispatchCfg Seq[Seq[FuType], dispatch limits]
  */
class Dispatch2IqSelect(numIn: Int, dispatchCfg: Seq[(Seq[Int], Int)])(implicit p: Parameters) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(Vec(numIn, ValidIO(new DynInst)))
    val out = MixedVec(dispatchCfg.map(x => Vec(x._2, ValidIO(new DynInst))))
    val mapIdxOH = Output(MixedVec(dispatchCfg.map(x => Vec(x._2, UInt(in.size.W))))) // OH mapping of in ports to out ports
  })

  val issuePortFuType: Seq[Seq[Int]] = dispatchCfg.map(_._1)

  val numOutKinds = io.out.size
  val numInPorts = io.in.size
  val numPortsOfKind = io.out.map(_.size)

  val canAcceptMatrix = Wire(Vec(numOutKinds, Vec(numInPorts, Bool())))

  for (inIdx <- 0 until numInPorts) {
    for (kindIdx <- io.out.indices) {
      canAcceptMatrix(kindIdx)(inIdx) := io.in(inIdx).valid && canAccept(issuePortFuType(kindIdx), io.in(inIdx).bits.fuType)
    }
  }

  val selectedIdxVec = canAcceptMatrix.zipWithIndex.map { case (outCanAcceptVec, kindIdx) =>
    val select = SelectOne("naive", outCanAcceptVec, numPortsOfKind(kindIdx))
    for (portIdx <- 0 until numPortsOfKind(kindIdx)) {
      val (selectValid, selectIdxOH) = select.getNthOH(portIdx + 1)
      io.out(kindIdx)(portIdx).valid := selectValid
      io.out(kindIdx)(portIdx).bits := Mux1H(selectIdxOH, io.in.map(_.bits))
      io.mapIdxOH(kindIdx)(portIdx) := selectIdxOH.asUInt
    }
  }

  def canAccept(acceptVec: Seq[Int], fuType: UInt): Bool = {
    (acceptVec.reduce(_ | _).U & fuType).orR
  }

  def canAccept(acceptVec: Seq[Seq[Int]], fuType: UInt): Vec[Bool] = {
    VecInit(acceptVec.map(x => canAccept(x, fuType)))
  }
}

/**
  * @author Yinan Xu, Xuan Hu
  */
class Dispatch2IqMemImp(override val wrapper: Dispatch2Iq)(implicit p: Parameters, params: SchdBlockParams)
  extends Dispatch2IqImp(wrapper)
    with HasXSParameter {

  import FuType._
  private val dispatchCfg: Seq[(Seq[Int], Int)] = Seq(
    (Seq(ldu), 2),
    (Seq(stu), 2),
  )

  private val enqLsqIO = io.enqLsqIO.get

  private val numLoadDeq = LoadPipelineWidth
  private val numStoreAMODeq = StorePipelineWidth
  private val numDeq = enqLsqIO.req.size
  private val numEnq = io.in.size

  val dispatchSelect = Module(new Dispatch2IqSelect(numIn = io.in.size, dispatchCfg = dispatchCfg))
  dispatchSelect.io.in := io.in
  private val selectOut = dispatchSelect.io.out
  private val selectIdxOH = dispatchSelect.io.mapIdxOH

  private val s0_enqLsq_resp = Wire(enqLsqIO.resp.cloneType)
  private val s0_out = Wire(io.out.cloneType)
  private val s0_blockedVec = Wire(Vec(io.in.size, Bool()))

  private val isLoadVec = VecInit(io.in.map(x => x.valid && FuType.isLoad(x.bits.fuType)))
  private val isStoreVec = VecInit(io.in.map(x => x.valid && FuType.isStore(x.bits.fuType)))
  private val isAMOVec = io.in.map(x => x.valid && FuType.isAMO(x.bits.fuType))
  private val isStoreAMOVec = io.in.map(x => x.valid && (FuType.isStore(x.bits.fuType) || FuType.isAMO(x.bits.fuType)))

  private val loadCntVec = VecInit(isLoadVec.indices.map(x => PopCount(isLoadVec.slice(0, x + 1))))
  private val storeAMOCntVec = VecInit(isStoreAMOVec.indices.map(x => PopCount(isStoreAMOVec.slice(0, x + 1))))

  val loadBlockVec = VecInit(loadCntVec.map(_ > numLoadDeq.U))
  val storeAMOBlockVec = VecInit(storeAMOCntVec.map(_ > numStoreAMODeq.U))
  val lsStructBlockVec = VecInit(loadBlockVec.zip(storeAMOBlockVec).map(x => x._1 || x._2))
  dontTouch(loadBlockVec)
  dontTouch(storeAMOBlockVec)
  dontTouch(lsStructBlockVec)
  dontTouch(isLoadVec)
  dontTouch(loadCntVec)

  for (i <- 0 until numEnq) {
    if (i >= numDeq) {
      s0_blockedVec(i) := true.B
    } else {
      s0_blockedVec(i) := lsStructBlockVec(i)
    }
  }

  // enqLsq io
  require(enqLsqIO.req.size == enqLsqIO.resp.size)
  for (i <- enqLsqIO.req.indices) {
    when (!io.in(i).valid) {
      enqLsqIO.needAlloc(i) := 0.U
    }.elsewhen(isStoreAMOVec(i)) {
      enqLsqIO.needAlloc(i) := 2.U // store | amo
    }.otherwise {
      enqLsqIO.needAlloc(i) := 1.U // load
    }
    enqLsqIO.req(i).valid := io.in(i).valid && !s0_blockedVec(i)
    enqLsqIO.req(i).bits := io.in(i).bits
    s0_enqLsq_resp(i) := enqLsqIO.resp(i)
  }

  // We always read physical register states when in gives the instructions.
  // This usually brings better timing.
  val reqPsrc = io.in.flatMap(in => in.bits.psrc.take(numIntSrc))
  require(io.readIntState.get.size >= reqPsrc.size, s"io.readIntState.get.size: ${io.readIntState.get.size}, psrc size: ${reqPsrc}")
  io.readIntState.get.map(_.req).zip(reqPsrc).foreach(x => x._1 := x._2)

  val intSrcStateVec = Wire(Vec(numEnq, Vec(numIntSrc, SrcState())))

  // srcState is read from outside and connected directly
  io.readIntState.get.map(_.resp).zip(intSrcStateVec.flatten).foreach(x => x._2 := x._1)

  val iqNotAllReady = !Cat(s0_out.map(_.map(_.ready)).flatten).andR
  val lsqCannotAccept = !enqLsqIO.canAccept

  for ((iqPorts, iqIdx) <- s0_out.zipWithIndex) {
    for ((port, portIdx) <- iqPorts.zipWithIndex) {
      println(s"[Dispatch2MemIQ] (iqIdx, portIdx): ($iqIdx, $portIdx)")
      when (iqNotAllReady || lsqCannotAccept) {
        s0_out.foreach(_.foreach(_.valid := false.B))
        s0_out.foreach(_.foreach(x => x.bits := 0.U.asTypeOf(x.bits)))
      }.otherwise {
        s0_out(iqIdx)(portIdx).valid := selectOut(iqIdx)(portIdx).valid && !Mux1H(selectIdxOH(iqIdx)(portIdx), s0_blockedVec)
        s0_out(iqIdx)(portIdx).bits := selectOut(iqIdx)(portIdx).bits // the same as Mux1H(selectIdxOH(iqIdx)(portIdx), io.in.map(_.bits))
        s0_out(iqIdx)(portIdx).bits.lqIdx := Mux1H(selectIdxOH(iqIdx)(portIdx), s0_enqLsq_resp.map(_.lqIdx))
        s0_out(iqIdx)(portIdx).bits.sqIdx := Mux1H(selectIdxOH(iqIdx)(portIdx), s0_enqLsq_resp.map(_.sqIdx))
      }
    }
  }

  s0_out.flatten.flatMap(_.bits.srcState.take(numIntSrc)).zip(intSrcStateVec.flatten).foreach(x => x._1 := x._2)

  // outToInMap(inIdx)(outIdx): the inst numbered inIdx will be accepted by port numbered outIdx
  val outToInMap: Vec[Vec[Bool]] = VecInit(selectIdxOH.flatten.map(x => x.asBools).transpose.map(x => VecInit(x)))
  val outReadyVec: Vec[Bool] = VecInit(s0_out.map(_.map(_.ready)).flatten)
  io.in.zipWithIndex.zip(outToInMap).foreach { case ((in, inIdx), outVec) =>
    when (iqNotAllReady || lsqCannotAccept) {
      in.ready := false.B
    }.otherwise {
      in.ready := (Cat(outVec) & Cat(outReadyVec)).orR && !s0_blockedVec(inIdx)
    }
  }
//  dontTouch(outToInMap)
//  dontTouch(outReadyVec)
//  dontTouch(s0_out)
//  dontTouch(s0_blockedVec)


  io.out <> s0_out
}
