package com.horizen.block

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util

import com.google.common.primitives.{Bytes, Ints, Longs}
import com.horizen.{ScorexEncoding, SidechainTypes}
import com.horizen.box.{Box, NoncedBox}
import com.horizen.companion.SidechainTransactionsCompanion
import com.horizen.params.NetworkParams
import com.horizen.proof.Signature25519
import com.horizen.proposition.{Proposition, PublicKey25519Proposition}
import com.horizen.secret.PrivateKey25519
import com.horizen.serialization.{JsonSerializable, JsonSerializer}
import com.horizen.transaction.{BoxTransaction, SidechainTransaction, Transaction}
import com.horizen.utils.{BytesUtils, ListSerializer}
import io.circe.Json
import scorex.core.block.Block
import scorex.core.{ModifierTypeId, NodeViewModifier, bytesToId, idToBytes}
import scorex.util.ModifierId
import scorex.core.serialization.ScorexSerializer
import scorex.core.utils.ScorexEncoder
import scorex.crypto.hash.Blake2b256
import scorex.util.serialization.{Reader, Writer}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.HashMap
import scala.util.{Success, Try}

class SidechainBlock (override val parentId: ModifierId,
                      override val timestamp: Block.Timestamp,
                      val mainchainBlocks : Seq[MainchainBlockReference],
                      val sidechainTransactions: Seq[SidechainTransaction[Proposition, NoncedBox[Proposition]]],
                      val forgerPublicKey: PublicKey25519Proposition,
                      val signature: Signature25519,
                      companion: SidechainTransactionsCompanion)
  extends Block[SidechainTypes#SCBT]
  with JsonSerializable
{

  override type M = SidechainBlock

  override lazy val serializer = new SidechainBlockSerializer(companion)

  override lazy val version: Block.Version = 0: Byte

  override val modifierTypeId: ModifierTypeId = SidechainBlock.ModifierTypeId

  override lazy val id: ModifierId =
    bytesToId(Blake2b256(messageToSign))

  override lazy val transactions: Seq[BoxTransaction[Proposition, Box[Proposition]]] = {
    var txs = Seq[BoxTransaction[Proposition, Box[Proposition]]]()

    for(b <- mainchainBlocks) {
      if (b.sidechainRelatedAggregatedTransaction.isDefined) {
        txs = txs :+ b.sidechainRelatedAggregatedTransaction.get
      }
    }
    for(tx <- sidechainTransactions)
      txs = txs :+ tx.asInstanceOf[BoxTransaction[Proposition, Box[Proposition]]]
    txs
  }

  lazy val messageToSign: Array[Byte] = {
    val sidechainTransactionsStream = new ByteArrayOutputStream
    sidechainTransactions.foreach {
      tx => sidechainTransactionsStream.write(tx.messageToSign())
    }

    val mainchainBlocksStream = new ByteArrayOutputStream
    mainchainBlocks.foreach {
      mcblock => mainchainBlocksStream.write(mcblock.bytes)
    }

    Bytes.concat(
      idToBytes(parentId),
      Longs.toByteArray(timestamp),
      sidechainTransactionsStream.toByteArray,
      mainchainBlocksStream.toByteArray,
      forgerPublicKey.bytes
    )
  }

  def semanticValidity(params: NetworkParams): Boolean = {
    if(parentId == null || parentId.length != 64
        || sidechainTransactions == null || sidechainTransactions.size > SidechainBlock.MAX_MC_SIDECHAIN_TXS_NUMBER
        || mainchainBlocks == null || mainchainBlocks.size > SidechainBlock.MAX_MC_BLOCKS_NUMBER
        || forgerPublicKey == null || signature == null)
      return false

    // Check if timestamp is valid and not too far in the future
    if(timestamp <= 0 || timestamp > Instant.now.getEpochSecond + 2 * 60 * 60) // 2 * 60 * 60 like in Horizen MC
      return false

    // check Block size
    val blockSize: Int = bytes.length
    if(blockSize > SidechainBlock.MAX_BLOCK_SIZE)
      return false

    // check, that signature is valid
    if(!signature.isValid(forgerPublicKey, messageToSign))
      return false

    // Check MainchainBlockReferences order in current block
    for(i <- 1 until mainchainBlocks.size) {
      if(!mainchainBlocks(i).header.hashPrevBlock.sameElements(mainchainBlocks(i-1).hash))
        return false
    }

    // check MainchainBlockReferences validity
    for(b <- mainchainBlocks)
      if(!b.semanticValidity(params))
        return false

    true
  }

  override def toJson: Json = {
    val arr: util.ArrayList[Json] = new util.ArrayList[Json]
    val values: mutable.HashMap[String, Json] = new mutable.HashMap[String, Json]
    val encoder: ScorexEncoder = new ScorexEncoder

    values.put("id", Json.fromString(encoder.encode(this.id)))
    values.put("parentId", Json.fromString(encoder.encode(this.parentId)))
    values.put("timestamp", Json.fromLong(this.timestamp))
    values.put("mainchainBlocks", Json.fromValues(this.mainchainBlocks.map(_.toJson)))
    values.put("sidechainTransactions", Json.fromValues(this.transactions.map(_.toJson)))
    values.put("forgerPublicKey", this.forgerPublicKey.toJson)
    values.put("signature", this.signature.toJson)

    Json.fromFields(values)
  }
}


object SidechainBlock extends ScorexEncoding {
  val MAX_BLOCK_SIZE = 2048 * 1024 //2048K
  val MAX_MC_BLOCKS_NUMBER = 3
  val MAX_MC_SIDECHAIN_TXS_NUMBER = 100000
  val ModifierTypeId: ModifierTypeId = scorex.core.ModifierTypeId @@ 3.toByte

  def create(parentId: Block.BlockId,
             timestamp: Block.Timestamp,
             mainchainBlocks : Seq[MainchainBlockReference],
             sidechainTransactions: Seq[SidechainTransaction[Proposition, NoncedBox[Proposition]]],
             ownerPrivateKey: PrivateKey25519,
             companion: SidechainTransactionsCompanion,
             params: NetworkParams,
             signatureOption: Option[Signature25519] = None // TO DO: later we should think about different unsigned/signed blocks creation methods
            ) : Try[SidechainBlock] = Try {
    require(parentId.length == 64)
    require(mainchainBlocks != null && mainchainBlocks.size <= SidechainBlock.MAX_MC_BLOCKS_NUMBER)
    require(sidechainTransactions != null)
    require(ownerPrivateKey != null)

    val signature = signatureOption match {
      case Some(signature) => signature
      case None =>
        val unsignedBlock: SidechainBlock = new SidechainBlock(
          parentId,
          timestamp,
          mainchainBlocks,
          sidechainTransactions,
          ownerPrivateKey.publicImage(),
          new Signature25519(new Array[Byte](Signature25519.SIGNATURE_LENGTH)), // empty signature
          companion
        )

        ownerPrivateKey.sign(unsignedBlock.messageToSign)
    }


    val block: SidechainBlock = new SidechainBlock(
      parentId,
      timestamp,
      mainchainBlocks,
      sidechainTransactions,
      ownerPrivateKey.publicImage(),
      signature,
      companion
    )

    if(!block.semanticValidity(params))
      throw new Exception("Sidechain Block is semantically invalid.")

    block
  }
}



class SidechainBlockSerializer(companion: SidechainTransactionsCompanion) extends ScorexSerializer[SidechainBlock] {
  private val _mcblocksSerializer: ListSerializer[MainchainBlockReference] = new ListSerializer[MainchainBlockReference](
    MainchainBlockReferenceSerializer,
    SidechainBlock.MAX_MC_BLOCKS_NUMBER
  )

  private val _sidechainTransactionsSerializer: ListSerializer[Transaction] = new ListSerializer[Transaction](
    companion,
    SidechainBlock.MAX_MC_SIDECHAIN_TXS_NUMBER
  )


  /*
  override def toBytes(obj: SidechainBlock): Array[Byte] = {
    val mcblocksBytes = _mcblocksSerializer.toBytes(obj.mainchainBlocks.toList.asJava)

    // TO DO: we should use SidechainTransactionsCompanion with all defined custom transactions
    val sidechainTransactionsBytes = _sidechainTransactionsSerializer.toBytes(obj.sidechainTransactions.map(t => t.asInstanceOf[Transaction]).toList.asJava)

    Bytes.concat(
      idToBytes(obj.parentId),                              // 32 bytes
      Longs.toByteArray(obj.timestamp),                     // 8 bytes
      Ints.toByteArray(mcblocksBytes.length),               // 4 bytes
      mcblocksBytes,                                        // total size of all MC Blocks
      Ints.toByteArray(sidechainTransactionsBytes.length),  // 4 bytes
      sidechainTransactionsBytes,                           // total size of all MC Blocks
      obj.forgerPublicKey.bytes,                                      // 32 bytes
      obj.signature.bytes                              // 64 bytes
    )
  }

  override def parseBytesTry(bytes: Array[Byte]): Try[SidechainBlock] = Try {
    require(bytes.length <= SidechainBlock.MAX_BLOCK_SIZE, "Unable to parse SidechainBlock bytes: reach out of maximum length.")
    require(bytes.length > 32 + 8 + 4 + 4 + 32 + 64, "Unable to parse SidechainBlock bytes: input data corrupted.") // size of empty block

    var offset: Int = 0

    val parentId = bytesToId(bytes.slice(offset, offset + 32))
    offset += 32

    val timestamp = BytesUtils.getLong(bytes, offset)
    offset += 8

    val mcblocksSize = BytesUtils.getInt(bytes, offset)
    offset += 4

    val mcblocks: Seq[MainchainBlockReference] = _mcblocksSerializer.parseBytesTry(bytes.slice(offset, offset + mcblocksSize)).get.asScala
    offset += mcblocksSize

    // to do: parse SC txs
    val sidechainTransactionsSize = BytesUtils.getInt(bytes, offset)
    offset += 4

    val sidechainTransactions: Seq[SidechainTransaction[Proposition, NoncedBox[Proposition]]] =
      _sidechainTransactionsSerializer.parseBytesTry(bytes.slice(offset, offset + mcblocksSize)).get
        .asScala
        .map(t => t.asInstanceOf[SidechainTransaction[Proposition, NoncedBox[Proposition]]])
    offset += sidechainTransactionsSize

    val owner = new PublicKey25519Proposition(bytes.slice(offset, offset + PublicKey25519Proposition.KEY_LENGTH))
    offset += PublicKey25519Proposition.KEY_LENGTH

    val ownerSignature = new Signature25519(bytes.slice(offset, offset + Signature25519.SIGNATURE_LENGTH))
    offset += Signature25519.SIGNATURE_LENGTH

    require(offset == bytes.length)

    new SidechainBlock(
      parentId,
      timestamp,
      mcblocks,
      sidechainTransactions,
      owner,
      ownerSignature,
      companion
    )

  }
  */

  override def serialize(obj: SidechainBlock, w: Writer): Unit = {
    w.putBytes(idToBytes(obj.parentId))
    w.putLong(obj.timestamp)

    val bw = w.newWriter()
    _mcblocksSerializer.serialize(obj.mainchainBlocks.toList.asJava, bw)
    w.putInt(bw.length())
    w.append(bw)

    val tw = w.newWriter()
    _sidechainTransactionsSerializer.serialize(obj.sidechainTransactions.map(t => t.asInstanceOf[Transaction]).toList.asJava, tw)
    w.putInt(tw.length())
    w.append(tw)

    w.putBytes(obj.forgerPublicKey.bytes())
    w.putBytes(obj.signature.bytes())
  }

  override def parse(r: Reader): SidechainBlock = {
    require(r.remaining <= SidechainBlock.MAX_BLOCK_SIZE)
    //require(r.remaining > 32 + 8 + 4 + 4 + 32 + 64) // size of empty block

    val parentId = bytesToId(r.getBytes(NodeViewModifier.ModifierIdSize))

    val timestamp = r.getLong()

    val mcbSize = r.getInt()

    if (r.remaining < mcbSize)
      throw new IllegalArgumentException("Input data corrupted.")

    val mcblocks: Seq[MainchainBlockReference] = _mcblocksSerializer.parse(r.newReader(r.getChunk(mcbSize))).asScala

    val txSize = r.getInt()

    if (r.remaining < txSize)
      throw new IllegalArgumentException("Input data corrupted.")

    val sidechainTransactions: Seq[SidechainTransaction[Proposition, NoncedBox[Proposition]]] =
      _sidechainTransactionsSerializer.parse(r.newReader(r.getChunk(txSize)))
        .asScala
        .map(t => t.asInstanceOf[SidechainTransaction[Proposition, NoncedBox[Proposition]]])

    val owner = new PublicKey25519Proposition(r.getBytes(PublicKey25519Proposition.KEY_LENGTH))

    val ownerSignature = new Signature25519(r.getBytes(Signature25519.SIGNATURE_LENGTH))

    new SidechainBlock(
      parentId,
      timestamp,
      mcblocks,
      sidechainTransactions,
      owner,
      ownerSignature,
      companion
    )
  }
}