package coop.rchain.casper.api

import cats.Monad
import cats.effect.Sync
import cats._, cats.data._, cats.implicits._
import cats.mtl._
import cats.mtl.implicits._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.{BlockDagRepresentation, BlockStore}
import coop.rchain.casper.Estimator.BlockHash
import coop.rchain.casper.MultiParentCasperRef.MultiParentCasperRef
import coop.rchain.casper.MultiParentCasper.ignoreDoppelgangerCheck
import coop.rchain.casper._
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper._
import coop.rchain.casper.util.rholang.InterpreterUtil
import coop.rchain.crypto.codec.Base16
import coop.rchain.crypto.hash.Blake2b512Random
import coop.rchain.graphz._
import coop.rchain.models.{BindPattern, Par}
import coop.rchain.models.rholang.sorter.Sortable
import coop.rchain.rspace.{Serialize, StableHashProvider}
import coop.rchain.rspace.trace.{COMM, Consume, Produce}
import coop.rchain.shared.{Log, SyncLock}
import coop.rchain.models.serialization.implicits.mkProtobufInstance
import coop.rchain.rholang.interpreter.{PrettyPrinter => RholangPrettyPrinter}
import coop.rchain.models.rholang.sorter.Sortable._
import monix.execution.Scheduler
import scodec.Codec

import scala.collection.immutable
import coop.rchain.catscontrib._
import coop.rchain.catscontrib.ski._
import coop.rchain.casper.util.{EventConverter, ProtoUtil}
import coop.rchain.casper._
import coop.rchain.casper.util.rholang.{InterpreterUtil, RuntimeManager}
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper.util.rholang.InterpreterUtil

object BlockAPI {

  private val createBlockLock = new SyncLock

  def deploy[F[_]: Monad: MultiParentCasperRef: Log](d: DeployData): F[DeployServiceResponse] = {
    def casperDeploy(implicit casper: MultiParentCasper[F]): F[DeployServiceResponse] =
      for {
        r <- MultiParentCasper[F].deploy(d)
        re <- r match {
               case Right(_)  => DeployServiceResponse(success = true, "Success!").pure[F]
               case Left(err) => DeployServiceResponse(success = false, err.getMessage).pure[F]
             }
      } yield re

    MultiParentCasperRef
      .withCasper[F, DeployServiceResponse](
        casperDeploy(_),
        DeployServiceResponse(success = false, s"Error: Casper instance not available")
      )
  }

  def createBlock[F[_]: Sync: MultiParentCasperRef: Log]: F[DeployServiceResponse] =
    MultiParentCasperRef.withCasper[F, DeployServiceResponse](
      casper =>
        // TODO: Use Bracket: See https://github.com/rchain/rchain/pull/1436#discussion_r215520914
        Monad[F].ifM(Sync[F].delay { createBlockLock.tryLock() })(
          for {
            maybeBlock <- casper.createBlock
            result <- maybeBlock match {
                       case err: NoBlock =>
                         DeployServiceResponse(success = false, s"Error while creating block: $err")
                           .pure[F]
                       case Created(block) =>
                         casper
                           .addBlock(block, ignoreDoppelgangerCheck[F])
                           .map(addResponse(_, block))
                     }
            _ <- Sync[F].delay { createBlockLock.unlock() }
          } yield result,
          DeployServiceResponse(success = false, "Error: There is another propose in progress.")
            .pure[F]
        ),
      DeployServiceResponse(success = false, "Error: Casper instance not available")
    )

  def getListeningNameDataResponse[F[_]: Sync: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      depth: Int,
      listeningName: Par
  )(implicit scheduler: Scheduler): F[ListeningNameDataResponse] = {
    def casperResponse(implicit casper: MultiParentCasper[F], channelCodec: Codec[Par]) =
      for {
        mainChain           <- getMainChainFromTip[F](depth)
        maybeRuntimeManager <- casper.getRuntimeManager
        runtimeManager      = maybeRuntimeManager.get // This is safe. Please reluctantly accept until runtimeManager is no longer exposed.
        sortedListeningName <- parSortable.sortMatch[F](listeningName).map(_.term)
        maybeBlocksWithActiveName <- mainChain.toList.traverse { block =>
                                      getDataWithBlockInfo[F](
                                        runtimeManager,
                                        sortedListeningName,
                                        block
                                      )
                                    }
        blocksWithActiveName = maybeBlocksWithActiveName.flatten
      } yield
        ListeningNameDataResponse(
          status = "Success",
          blockResults = blocksWithActiveName,
          length = blocksWithActiveName.length
        )

    implicit val channelCodec: Codec[Par] = Serialize[Par].toCodec
    MultiParentCasperRef.withCasper[F, ListeningNameDataResponse](
      casperResponse(_, channelCodec),
      ListeningNameDataResponse(status = "Error: Casper instance not available")
    )
  }

  def getListeningNameContinuationResponse[F[_]: Sync: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      depth: Int,
      listeningNames: Seq[Par]
  )(implicit scheduler: Scheduler): F[ListeningNameContinuationResponse] = {
    def casperResponse(implicit casper: MultiParentCasper[F], channelCodec: Codec[Par]) =
      for {
        mainChain           <- getMainChainFromTip[F](depth)
        maybeRuntimeManager <- casper.getRuntimeManager
        runtimeManager      = maybeRuntimeManager.get // This is safe. Please reluctantly accept until runtimeManager is no longer exposed.
        sortedListeningNames <- listeningNames.toList
                                 .traverse(parSortable.sortMatch[F](_).map(_.term))
        maybeBlocksWithActiveName <- mainChain.toList.traverse { block =>
                                      getContinuationsWithBlockInfo[F](
                                        runtimeManager,
                                        sortedListeningNames,
                                        block
                                      )
                                    }
        blocksWithActiveName = maybeBlocksWithActiveName.flatten
      } yield
        ListeningNameContinuationResponse(
          status = "Success",
          blockResults = blocksWithActiveName,
          length = blocksWithActiveName.length
        )

    implicit val channelCodec: Codec[Par] = Serialize[Par].toCodec
    MultiParentCasperRef.withCasper[F, ListeningNameContinuationResponse](
      casperResponse(_, channelCodec),
      ListeningNameContinuationResponse(status = "Error: Casper instance not available")
    )
  }

  private def getMainChainFromTip[F[_]: Monad: MultiParentCasper: Log: SafetyOracle: BlockStore](
      depth: Int
  ): F[IndexedSeq[BlockMessage]] =
    for {
      dag       <- MultiParentCasper[F].blockDag
      estimates <- MultiParentCasper[F].estimator(dag)
      tip       = estimates.head
      mainChain <- ProtoUtil.getMainChainUntilDepth[F](tip, IndexedSeq.empty[BlockMessage], depth)
    } yield mainChain

  private def getDataWithBlockInfo[F[_]: Monad: MultiParentCasper: Log: SafetyOracle: BlockStore](
      runtimeManager: RuntimeManager,
      sortedListeningName: Par,
      block: BlockMessage
  )(implicit channelCodec: Codec[Par], scheduler: Scheduler): F[Option[DataWithBlockInfo]] =
    if (isListeningNameReduced(block, immutable.Seq(sortedListeningName))) {
      val stateHash =
        ProtoUtil.tuplespace(block).get
      val data =
        runtimeManager.getData(stateHash, sortedListeningName)
      for {
        blockInfo <- getBlockInfoWithoutTuplespace[F](block)
      } yield Option[DataWithBlockInfo](DataWithBlockInfo(data, Some(blockInfo)))
    } else {
      none[DataWithBlockInfo].pure[F]
    }

  private def getContinuationsWithBlockInfo[F[_]: Monad: MultiParentCasper: Log: SafetyOracle: BlockStore](
      runtimeManager: RuntimeManager,
      sortedListeningNames: immutable.Seq[Par],
      block: BlockMessage
  )(
      implicit channelCodec: Codec[Par],
      scheduler: Scheduler
  ): F[Option[ContinuationsWithBlockInfo]] =
    if (isListeningNameReduced(block, sortedListeningNames)) {
      val stateHash =
        ProtoUtil.tuplespace(block).get
      val continuations: Seq[(Seq[BindPattern], Par)] =
        runtimeManager.getContinuation(stateHash, sortedListeningNames)
      val continuationInfos = continuations.map(
        continuation => WaitingContinuationInfo(continuation._1, Some(continuation._2))
      )
      for {
        blockInfo <- getBlockInfoWithoutTuplespace[F](block)
      } yield
        Option[ContinuationsWithBlockInfo](
          ContinuationsWithBlockInfo(continuationInfos, Some(blockInfo))
        )
    } else {
      none[ContinuationsWithBlockInfo].pure[F]
    }

  private def isListeningNameReduced(
      block: BlockMessage,
      sortedListeningName: immutable.Seq[Par]
  )(implicit channelCodec: Codec[Par]) = {
    val serializedLog = for {
      bd    <- block.body.toSeq
      pd    <- bd.deploys
      event <- pd.log
    } yield event
    val log =
      serializedLog.map(EventConverter.toRspaceEvent)
    log.exists {
      case Produce(channelHash, _, _) =>
        channelHash == StableHashProvider.hash(sortedListeningName)
      case Consume(channelsHashes, _, _) =>
        channelsHashes.toList.sorted == sortedListeningName
          .map(StableHashProvider.hash(_))
          .toList
          .sorted
      case COMM(consume, produces) =>
        (consume.channelsHashes.toList.sorted ==
          sortedListeningName.map(StableHashProvider.hash(_)).toList.sorted) ||
          produces.exists(
            produce => produce.channelsHash == StableHashProvider.hash(sortedListeningName)
          )
    }
  }

  // TOOD extract common code from show blocks
  def visualizeBlocks[F[_]: Monad: Sync: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      d: Option[Int] = None
  ): F[String] = {

    type Effect[A] = StateT[Id, StringBuffer, A]
    implicit val ser = new StringSerializer[Effect]
    case class Acc(timeseries: List[Long] = List.empty, graph: Effect[Graphz[Effect]])

    def casperResponse(implicit casper: MultiParentCasper[F]): F[String] =
      for {
        dag         <- MultiParentCasper[F].blockDag
        maxHeight   <- dag.topoSort(0L).map(_.length - 1)
        depth       = d.getOrElse(maxHeight)
        startHeight = math.max(0, maxHeight - depth)
        topoSort    <- dag.topoSortTail(depth)
        acc <- topoSort.foldM(Acc(graph = Graphz[Effect]("DAG", DiGraph, rankdir = Some(BT)))) {
                case (acc, blockHashes) =>
                  for {
                    blocks    <- blockHashes.traverse(ProtoUtil.unsafeGetBlock[F])
                    timeEntry = blocks.head.getBody.getState.blockNumber
                    maybeLvl0 = if (timeEntry != 1) None
                    else
                      Some(for {
                        g       <- Graphz.subgraph[Effect](s"lvl0", DiGraph, rank = Some(Same))
                        _       <- g.node("0")
                        genesis = blocks.head.getHeader.parentsHashList.head
                        _       <- g.node(name = PrettyPrinter.buildString(genesis), shape = Msquare)
                        _       <- g.close
                      } yield g)

                    lvlGraph = for {
                      g <- Graphz.subgraph[Effect](s"lvl$timeEntry", DiGraph, rank = Some(Same))
                      _ <- g.node(timeEntry.toString)
                      _ <- blocks.traverse(
                            b => g.node(name = PrettyPrinter.buildString(b.blockHash), shape = Box)
                          )
                      _ <- g.close
                    } yield g
                    graph = for {
                      g <- acc.graph
                      _ <- maybeLvl0.getOrElse(().pure[Effect])
                      _ <- g.subgraph(lvlGraph)
                      _ <- blocks.traverse(
                            b =>
                              b.getHeader.parentsHashList.toList
                                .map(PrettyPrinter.buildString)
                                .traverse { parentHash =>
                                  g.edge(PrettyPrinter.buildString(b.blockHash) -> parentHash)
                                }
                          )
                    } yield g
                  } yield {
                    val timeEntries = timeEntry :: maybeLvl0.map(kp(0L)).toList
                    acc.copy(
                      timeseries = timeEntries ++ acc.timeseries,
                      graph = graph
                    )
                  }

              }
        result <- Sync[F].delay {

                   val times = acc.timeseries.sorted.map(_.toString)

                   val timeseries: Effect[Graphz[Effect]] = for {
                     g     <- Graphz.subgraph[Effect]("timeseries", DiGraph)
                     _     <- times.traverse(n => g.node(name = n, shape = PlainText))
                     edges = times.zip(times.drop(1))
                     _     <- edges.traverse(g.edge)
                     _     <- g.close
                   } yield g

                   val finalGraph: Effect[Graphz[Effect]] = for {
                     g <- acc.graph
                     _ <- g.subgraph(timeseries)
                     _ <- g.close
                   } yield g
                   finalGraph.runS(new StringBuffer).toString
                 }
      } yield result

    MultiParentCasperRef.withCasper[F, String](
      casperResponse(_),
      "no casper"
    )
  }

  def showBlocks[F[_]: Monad: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      depth: Int
  ): F[List[BlockInfoWithoutTuplespace]] = {
    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        dag         <- MultiParentCasper[F].blockDag
        maxHeight   <- dag.topoSort(0L).map(_.length - 1) // TODO: Optimize calculating max height
        startHeight = math.max(0, maxHeight - depth)
        flattenedBlockInfosUntilDepth <- getFlattenedBlockInfosUntilDepth[F](
                                          depth,
                                          dag
                                        )
      } yield flattenedBlockInfosUntilDepth.reverse

    MultiParentCasperRef.withCasper[F, List[BlockInfoWithoutTuplespace]](
      casperResponse(_),
      List.empty[BlockInfoWithoutTuplespace]
    )
  }

  private def getFlattenedBlockInfosUntilDepth[F[_]: Monad: MultiParentCasper: Log: SafetyOracle: BlockStore](
      depth: Int,
      dag: BlockDagRepresentation[F]
  ): F[List[BlockInfoWithoutTuplespace]] =
    for {
      topoSort <- dag.topoSortTail(depth)
      result <- topoSort.foldM(List.empty[BlockInfoWithoutTuplespace]) {
                 case (blockInfosAtHeightAcc, blockHashesAtHeight) =>
                   for {
                     blocksAtHeight     <- blockHashesAtHeight.traverse(ProtoUtil.unsafeGetBlock[F])
                     blockInfosAtHeight <- blocksAtHeight.traverse(getBlockInfoWithoutTuplespace[F])
                   } yield blockInfosAtHeightAcc ++ blockInfosAtHeight
               }
    } yield result

  def showMainChain[F[_]: Monad: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      depth: Int
  ): F[List[BlockInfoWithoutTuplespace]] = {
    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        dag        <- MultiParentCasper[F].blockDag
        estimates  <- MultiParentCasper[F].estimator(dag)
        tip        = estimates.head
        mainChain  <- ProtoUtil.getMainChainUntilDepth[F](tip, IndexedSeq.empty[BlockMessage], depth)
        blockInfos <- mainChain.toList.traverse(getBlockInfoWithoutTuplespace[F])
      } yield blockInfos

    MultiParentCasperRef.withCasper[F, List[BlockInfoWithoutTuplespace]](
      casperResponse(_),
      List.empty[BlockInfoWithoutTuplespace]
    )
  }

  // TODO: Replace with call to BlockStore
  def findBlockWithDeploy[F[_]: Monad: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      user: ByteString,
      timestamp: Long
  ): F[BlockQueryResponse] = {
    def casperResponse(implicit casper: MultiParentCasper[F]): F[BlockQueryResponse] =
      for {
        dag                <- MultiParentCasper[F].blockDag
        allBlocksTopoSort  <- dag.topoSort(0L)
        maybeBlock         <- findBlockWithDeploy[F](allBlocksTopoSort.flatten.reverse, user, timestamp)
        blockQueryResponse <- maybeBlock.traverse(getFullBlockInfo[F])
      } yield
        blockQueryResponse.fold(
          BlockQueryResponse(
            status = s"Error: Failure to find block containing deploy signed by ${PrettyPrinter
              .buildString(user)} with timestamp ${timestamp.toString}"
          )
        )(
          blockInfo =>
            BlockQueryResponse(
              status = "Success",
              blockInfo = Some(blockInfo)
            )
        )

    MultiParentCasperRef.withCasper[F, BlockQueryResponse](
      casperResponse(_),
      BlockQueryResponse(status = "Error: Casper instance not available")
    )
  }

  private def findBlockWithDeploy[F[_]: Monad: Log: BlockStore](
      blockHashes: Vector[BlockHash],
      user: ByteString,
      timestamp: Long
  ): F[Option[BlockMessage]] =
    blockHashes.toStream
      .traverse(ProtoUtil.unsafeGetBlock[F](_))
      .map(blocks => blocks.find(ProtoUtil.containsDeploy(_, user, timestamp)))

  def showBlock[F[_]: Monad: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      q: BlockQuery
  ): F[BlockQueryResponse] = {
    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        dag        <- MultiParentCasper[F].blockDag
        maybeBlock <- getBlock[F](q, dag)
        blockQueryResponse <- maybeBlock match {
                               case Some(block) =>
                                 for {
                                   blockInfo <- getFullBlockInfo[F](block)
                                 } yield
                                   BlockQueryResponse(
                                     status = "Success",
                                     blockInfo = Some(blockInfo)
                                   )
                               case None =>
                                 BlockQueryResponse(
                                   status = s"Error: Failure to find block with hash ${q.hash}"
                                 ).pure[F]
                             }
      } yield blockQueryResponse

    MultiParentCasperRef.withCasper[F, BlockQueryResponse](
      casperResponse(_),
      BlockQueryResponse(status = "Error: Casper instance not available")
    )
  }

  private def getBlockInfo[A, F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage,
      constructor: (
          BlockMessage,
          Long,
          Int,
          BlockHash,
          Long,
          BlockHash,
          Seq[BlockHash],
          Float,
          Float
      ) => F[A]
  ): F[A] =
    for {
      dag         <- MultiParentCasper[F].blockDag
      header      = block.header.getOrElse(Header.defaultInstance)
      version     = header.version
      deployCount = header.deployCount
      tsHash = ProtoUtil.tuplespace(block) match {
        case Some(hash) => hash
        case None       => ByteString.EMPTY
      }
      timestamp                = header.timestamp
      mainParent               = header.parentsHashList.headOption.getOrElse(ByteString.EMPTY)
      parentsHashList          = header.parentsHashList
      normalizedFaultTolerance <- SafetyOracle[F].normalizedFaultTolerance(dag, block.blockHash)
      initialFault             <- MultiParentCasper[F].normalizedInitialFault(ProtoUtil.weightMap(block))
      blockInfo <- constructor(
                    block,
                    version,
                    deployCount,
                    tsHash,
                    timestamp,
                    mainParent,
                    parentsHashList,
                    normalizedFaultTolerance,
                    initialFault
                  )
    } yield blockInfo

  private def getFullBlockInfo[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage
  ): F[BlockInfo] = getBlockInfo[BlockInfo, F](block, constructBlockInfo[F])
  private def getBlockInfoWithoutTuplespace[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage
  ): F[BlockInfoWithoutTuplespace] =
    getBlockInfo[BlockInfoWithoutTuplespace, F](block, constructBlockInfoWithoutTuplespace[F])

  private def constructBlockInfo[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage,
      version: Long,
      deployCount: Int,
      tsHash: BlockHash,
      timestamp: Long,
      mainParent: BlockHash,
      parentsHashList: Seq[BlockHash],
      normalizedFaultTolerance: Float,
      initialFault: Float
  ): F[BlockInfo] =
    for {
      tsDesc <- MultiParentCasper[F].storageContents(tsHash)
    } yield
      BlockInfo(
        blockHash = PrettyPrinter.buildStringNoLimit(block.blockHash),
        blockSize = block.serializedSize.toString,
        blockNumber = ProtoUtil.blockNumber(block),
        version = version,
        deployCount = deployCount,
        tupleSpaceHash = PrettyPrinter.buildStringNoLimit(tsHash),
        tupleSpaceDump = tsDesc,
        timestamp = timestamp,
        faultTolerance = normalizedFaultTolerance - initialFault,
        mainParentHash = PrettyPrinter.buildStringNoLimit(mainParent),
        parentsHashList = parentsHashList.map(PrettyPrinter.buildStringNoLimit),
        sender = PrettyPrinter.buildStringNoLimit(block.sender),
        shardId = block.shardId
      )

  private def constructBlockInfoWithoutTuplespace[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage,
      version: Long,
      deployCount: Int,
      tsHash: BlockHash,
      timestamp: Long,
      mainParent: BlockHash,
      parentsHashList: Seq[BlockHash],
      normalizedFaultTolerance: Float,
      initialFault: Float
  ): F[BlockInfoWithoutTuplespace] =
    BlockInfoWithoutTuplespace(
      blockHash = PrettyPrinter.buildStringNoLimit(block.blockHash),
      blockSize = block.serializedSize.toString,
      blockNumber = ProtoUtil.blockNumber(block),
      version = version,
      deployCount = deployCount,
      tupleSpaceHash = PrettyPrinter.buildStringNoLimit(tsHash),
      timestamp = timestamp,
      faultTolerance = normalizedFaultTolerance - initialFault,
      mainParentHash = PrettyPrinter.buildStringNoLimit(mainParent),
      parentsHashList = parentsHashList.map(PrettyPrinter.buildStringNoLimit),
      sender = PrettyPrinter.buildStringNoLimit(block.sender)
    ).pure[F]

  private def getBlock[F[_]: Monad: MultiParentCasper: BlockStore](
      q: BlockQuery,
      dag: BlockDagRepresentation[F]
  ): F[Option[BlockMessage]] =
    for {
      findResult <- BlockStore[F].find(h => {
                     Base16.encode(h.toByteArray).startsWith(q.hash)
                   })
    } yield
      findResult.headOption match {
        case Some((_, block)) =>
          Some(block)
        case None =>
          none[BlockMessage]
      }

  private def addResponse(status: BlockStatus, block: BlockMessage): DeployServiceResponse =
    status match {
      case _: InvalidBlock =>
        DeployServiceResponse(success = false, s"Failure! Invalid block: $status")
      case _: ValidBlock =>
        val hash = PrettyPrinter.buildString(block.blockHash)
        DeployServiceResponse(success = true, s"Success! Block $hash created and added.")
      case BlockException(ex) =>
        DeployServiceResponse(success = false, s"Error during block processing: $ex")
      case Processing =>
        DeployServiceResponse(
          success = false,
          "No action taken since other thread is already processing the block."
        )
    }

  def previewPrivateNames[F[_]: Monad: Log](
      user: ByteString,
      timestamp: Long,
      nameQty: Int
  ): F[PrivateNamePreviewResponse] = {
    val seed    = DeployData().withUser(user).withTimestamp(timestamp)
    val rand    = Blake2b512Random(DeployData.toByteArray(seed))
    val safeQty = nameQty min 1024
    val ids     = (0 until safeQty).map(_ => ByteString.copyFrom(rand.next()))
    PrivateNamePreviewResponse(ids).pure[F]
  }
}
