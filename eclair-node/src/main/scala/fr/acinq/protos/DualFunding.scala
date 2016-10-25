package fr.acinq.protos

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{OutPoint, _}
import fr.acinq.eclair.blockchain.ExtendedBitcoinClient
import fr.acinq.eclair.channel.Helpers._
import fr.acinq.eclair.channel._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.ExtendedBitcoinClient.FundTransactionResponse
import lightning.locktime
import lightning.locktime.Locktime.Blocks

/**
  * Alice --- balance, inputs, change outputs --> Bob
  * Alice <-- balance, inputs, change outputs --- Bob
  * Alice --- funding txid + output index,
  * balances, Bob's commit tx sig   --> Bob
  * Alice <-- funding tx sig,
  * Alice's commit tx sig           --- Bob
  */
object DualFunding extends App {
  val anchorAmount = 1000000L

  object Alice {
    val (Base58.Prefix.SecretKeyTestnet, commitPrivKey) = Base58Check.decode("cQPmcNr6pwBQPyGfab3SksE9nTCtx9ism9T4dkS9dETNU2KKtJHk")
    val (Base58.Prefix.SecretKeyTestnet, finalPrivKey) = Base58Check.decode("cUrAtLtV7GGddqdkhUxnbZVDWGJBTducpPoon3eKp9Vnr1zxs6BG")
    val channelParams = OurChannelParams(locktime(Blocks(300)), commitPrivKey, finalPrivKey, 1, 10000, Crypto.sha256("alice-seed".getBytes()), Some(Satoshi(anchorAmount)))
    val finalPubKey = channelParams.finalPubKey

    def revocationHash(index: Long) = Helpers.revocationHash(channelParams.shaSeed, index)

    def ourSpec = CommitmentSpec(Set.empty[Htlc], feeRate = Alice.channelParams.initialFeeRate, amount_them_msat = 0, amount_us_msat = anchorAmount * 1000)

    def theirSpec = CommitmentSpec(Set.empty[Htlc], feeRate = Bob.channelParams.initialFeeRate, amount_them_msat = anchorAmount * 1000, amount_us_msat = 0)
  }

  object Bob {
    val (Base58.Prefix.SecretKeyTestnet, commitPrivKey) = Base58Check.decode("cSUwLtdZ2tht9ZmHhdQue48pfe7tY2GT2TGWJDtjoZgo6FHrubGk")
    val (Base58.Prefix.SecretKeyTestnet, finalPrivKey) = Base58Check.decode("cPR7ZgXpUaDPA3GwGceMDS5pfnSm955yvks3yELf3wMJwegsdGTg")
    val channelParams = OurChannelParams(locktime(Blocks(350)), commitPrivKey, finalPrivKey, 2, 10000, Crypto.sha256("bob-seed".getBytes()), None)
    val finalPubKey = channelParams.finalPubKey

    def revocationHash(index: Long) = Helpers.revocationHash(channelParams.shaSeed, index)

    def ourSpec = Alice.theirSpec

    def theirSpec = Alice.ourSpec
  }

  sealed trait Message

  case class OpenRequest(balance: Satoshi, publicKey: BinaryData, delay: locktime) extends Message

  case class OpenResponse(balance: Satoshi, inputs: Seq[TxIn], changeOutputs: Seq[TxOut], publicKey: BinaryData, delay: locktime) extends Message

  case class SignatureRequest(anchorTxId: BinaryData, anchorOutputIndex: Int, commitSig: BinaryData) extends Message

  case class SignatureResponse(fundingSig: BinaryData, commitSig: BinaryData) extends Message

  val system = ActorSystem("mySystem")
  val config = ConfigFactory.load()
  val bitcoin_client = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(
    user = config.getString("eclair.bitcoind.rpcuser"),
    password = config.getString("eclair.bitcoind.rpcpassword"),
    host = config.getString("eclair.bitcoind.host"),
    port = config.getInt("eclair.bitcoind.rpcport")))

  val (_, priv1) = Base58Check.decode("cV5oyXUgySSMcUvKNdKtuYg4t4NTaxkwYrrocgsJZuYac2ogEdZX")
  val (_, priv2) = Base58Check.decode("cNcttRx29WcftwAwKQESCQX8ZMTVykb1s5Zjpse4D6QCzwKPEL5H")

  class Channel(ourParams: OurChannelParams) extends Actor with ActorLogging {

    import scala.concurrent.ExecutionContext.Implicits.global

    val balance = 10000 satoshi

    def receive = {
      case ('connect, amount: Satoshi, them: ActorRef) =>
        them ! OpenRequest(amount, ourParams.commitPubKey, ourParams.delay)
        context become waitingForOpenResponse(amount)

      case theirRequest@OpenRequest(theirBalance, theirPubKey, theirDelay) =>
        val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(balance, Script.pay2wpkh(ourParams.commitPubKey)) :: Nil, lockTime = 0)
        val replyTo = sender
        // ask bitcoind to select inputs and change outputs
        for {
          fund <- bitcoin_client.fundTransaction(tx)
          tx1 = fund.tx
          change = tx1.txOut(fund.changepos)
          outputs = change :: Nil
        } yield {
          val ourResponse = OpenResponse(balance, tx1.txIn, outputs, ourParams.commitPubKey, ourParams.delay)
          replyTo ! ourResponse
          context become waitingForSignatureRequest(theirRequest, ourResponse)
        }
    }

    def waitingForOpenResponse(balance: Satoshi): Receive = {
      case theirResponse@OpenResponse(theirBalance, theirInputs, theirOutputs, theirPub, theirDelay) =>
        // create incomplete anchor tx
        val anchorAmount = balance + theirBalance
        val anchorOutput = TxOut(amount = anchorAmount, publicKeyScript = Scripts.anchorPubkeyScript(ourParams.commitPubKey, theirPub))

        val tx = Transaction(version = 2,
          txIn = theirInputs,
          txOut = anchorOutput +: theirOutputs,
          lockTime = 0)
        // it is not complete because it is missing our inputs and change outputs

        bitcoin_client.fundTransaction(tx).pipeTo(self)
        context become waitingForAnchor(sender, theirResponse, anchorOutput)
    }

    def waitingForAnchor(them: ActorRef, theirResponse: OpenResponse, anchorOutput: TxOut): Receive = {
      case FundTransactionResponse(tx, changepos, fee) =>
        // tx has our inputs and their inputs
        val anchorTx = Scripts.permuteInputs(Scripts.permuteOutputs(tx))
        val index = anchorTx.txOut.indexOf(anchorOutput)
        val theirParams = TheirChannelParams(theirResponse.delay, theirResponse.publicKey, theirResponse.publicKey, Some(1), 100)

        val ourSpec = CommitmentSpec(Set.empty[Htlc], feeRate = 0, amount_them_msat = theirResponse.balance.amount * 1000, amount_us_msat = 0)
        val theirSpec = CommitmentSpec(Set.empty[Htlc], feeRate = 0, amount_them_msat = theirResponse.balance.amount * 1000, amount_us_msat = balance.amount * 1000)
        val theirTx = makeTheirTx(ourParams, theirParams, TxIn(OutPoint(tx.hash, index), Array.emptyByteArray, 0xffffffffL) :: Nil, Hash.Zeroes, theirSpec)
        val ourSigForThem = sign(ourParams, theirParams, anchorOutput.amount, theirTx)
        them ! SignatureRequest(tx.txid, index, ourSigForThem)
        context become waitingForSignatureResponse()
    }

    def waitingForSignatureResponse(): Receive = ???

    def waitingForSignatureRequest(theirRequest: OpenRequest, ourResponse: OpenResponse): Receive = {
      case SignatureRequest(anchorTxId, anchorOutputIndex, ourCommitSig) =>
        val anchorAmount = theirRequest.balance + ourResponse.balance
        val anchorOutput = TxOut(amount = anchorAmount, publicKeyScript = Scripts.anchorPubkeyScript(ourParams.commitPubKey, theirRequest.publicKey))
        val anchorTx = Transaction(version = 2,
          txIn = ourResponse.inputs,
          txOut = anchorOutput +: ourResponse.changeOutputs,
          lockTime = 0)

    }
  }


  val a = system.actorOf(Props(new Channel(Alice.channelParams)), "Alice")
  val b = system.actorOf(Props(new Channel(Bob.channelParams)), "Bob")

  a ! ('connect, 42000 satoshi, b)
}
