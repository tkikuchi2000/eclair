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
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.protos.SegwitFunding.FundingInputs
import lightning.{locktime, update_commit}
import lightning.locktime.Locktime.Blocks
import org.bouncycastle.util.encoders.Hex

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

  case class OpenRequest(balance: Satoshi, input: TxIn, changeOutputs: Seq[TxOut], commitKey: BinaryData, finalKey: BinaryData, delay: locktime) extends Message

  case class OpenResponse(balance: Satoshi, input: TxIn, changeOutputs: Seq[TxOut], commitKey: BinaryData, finalKey: BinaryData, delay: locktime) extends Message

  case class SignatureRequest(commitSig: BinaryData) extends Message

  case class SignatureResponse(fundingSig: BinaryData, commitSig: BinaryData) extends Message

  val system = ActorSystem("mySystem")
  val config = ConfigFactory.load()
  val bitcoin = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(
    user = config.getString("eclair.bitcoind.rpcuser"),
    password = config.getString("eclair.bitcoind.rpcpassword"),
    host = config.getString("eclair.bitcoind.host"),
    port = config.getInt("eclair.bitcoind.rpcport")))

  class Channel(ourParams: OurChannelParams) extends Actor with ActorLogging {

    import scala.concurrent.ExecutionContext.Implicits.global

    val balance = 10000 satoshi
    val fee = 1000 satoshi

    def receive = {
      case ('connect, amount: Satoshi, them: ActorRef) =>
        SegwitFunding.fundSegwitTx(bitcoin)(amount, ourParams.commitPubKey).map(fundingInputs => {
          // TODO: publish and confirm
          bitcoin.publishTransaction(fundingInputs.publishTx)
          them ! OpenRequest(amount, fundingInputs.input, fundingInputs.changeOutputs, ourParams.commitPubKey, ourParams.finalPubKey, ourParams.delay)
          context become waitingForOpenResponse(amount, fundingInputs)
        })

      case theirRequest@OpenRequest(theirBalance, theirInputs, theirOutputs, theirCommitKey, theirFinalKey, theirDelay) =>
        val replyTo = sender
        SegwitFunding.fundSegwitTx(bitcoin)(balance, ourParams.commitPubKey).map(fundingInputs => {
          // TODO: publish and confirm
          bitcoin.publishTransaction(fundingInputs.publishTx)
          val ourResponse = OpenResponse(balance, fundingInputs.input, fundingInputs.changeOutputs, ourParams.commitPubKey, ourParams.finalPubKey, ourParams.delay)
          replyTo ! ourResponse
          context become waitingForSignatureRequest(theirRequest, fundingInputs)
        })
    }

    def waitingForOpenResponse(balance: Satoshi, fundingInputs: FundingInputs): Receive = {
      case theirResponse@OpenResponse(theirBalance, theirInput, theirOutputs, theirCommitKey, theirFinalKey, theirDelay) =>
        // create incomplete anchor tx
        val balance1 = balance - fee
        val theirBalance1 = theirBalance - fee
        val anchorAmount = balance1 + theirBalance1
        val anchorOutput = TxOut(amount = anchorAmount, publicKeyScript = Scripts.anchorPubkeyScript(ourParams.commitPubKey, theirCommitKey))

        val tx = Transaction(version = 2,
          txIn = fundingInputs.input :: theirInput :: Nil,
          txOut = anchorOutput :: Nil,
          lockTime = 0)
        val anchorTx = Scripts.permuteInputs(Scripts.permuteOutputs(tx))
        val index = anchorTx.txOut.indexOf(anchorOutput)
        log.info(s"creating anchor tx ${anchorTx.txid}")

        val theirParams = TheirChannelParams(theirResponse.delay, theirResponse.commitKey, theirResponse.finalKey, Some(1), 100)
        val theirSpec = CommitmentSpec(Set.empty[Htlc], feeRate = 0, amount_them_msat = balance1.amount * 1000, amount_us_msat = theirBalance1.amount * 1000)
        val theirTx = makeTheirTx(ourParams, theirParams, TxIn(OutPoint(anchorTx, index), Array.emptyByteArray, 0xffffffffL) :: Nil, Hash.Zeroes, theirSpec)
        log.info(s"signing their tx: $theirTx")
        val ourSigForThem = sign(ourParams, theirParams, anchorOutput.amount, theirTx)
        val theirRevocationHash = Hash.Zeroes
        val theirNextRevocationHash = Hash.Zeroes

        val ourSpec = CommitmentSpec(Set.empty[Htlc], feeRate = 0, amount_them_msat = theirBalance1.amount * 1000, amount_us_msat = balance1.amount * 1000)
        val ourRevocationHash = Hash.Zeroes
        val ourTx = makeOurTx(ourParams, theirParams, TxIn(OutPoint(anchorTx, index), Array.emptyByteArray, 0xffffffffL) :: Nil, ourRevocationHash, ourSpec)

        val commitments = Commitments(ourParams, theirParams,
          OurCommit(0, ourSpec, ourTx), TheirCommit(0, theirSpec, theirTx.txid, theirRevocationHash),
          fr.acinq.eclair.channel.OurChanges(Nil, Nil, Nil), fr.acinq.eclair.channel.TheirChanges(Nil, Nil), 0L,
          Right(theirNextRevocationHash), anchorOutput, ShaChain.init, new BasicTxDb)

        sender ! SignatureRequest(ourSigForThem)
        context become waitingForSignatureResponse(commitments, fundingInputs, anchorTx, theirInput)
    }

    def waitingForSignatureResponse(commitments: Commitments, fundingInputs: FundingInputs, anchorTx: Transaction, theirInput: TxIn): Receive = {
      case SignatureResponse(theirFundingSig, theirSigForUs) =>
        log.info(s"checking their signature for the anchor tx")
        val ourInputIndex = anchorTx.txIn.indexOf(fundingInputs.input)
        val fundingSig = Transaction.signInput(anchorTx, ourInputIndex, Script.pay2pkh(ourParams.commitPubKey), SIGHASH_ANYONECANPAY | SIGHASH_ALL, fundingInputs.amount, 1, ourParams.commitPrivKey)
        val theirInputIndex = anchorTx.txIn.indexOf(theirInput)
        val signedAnchorTx = anchorTx
          .updateWitness(ourInputIndex, ScriptWitness(fundingSig :: ourParams.commitPubKey :: Nil))
          .updateWitness(theirInputIndex, ScriptWitness(theirFundingSig :: commitments.theirParams.commitPubKey :: Nil))
        println(s"anchor tx: ${Hex.toHexString(Transaction.write(signedAnchorTx).toArray)}")
        bitcoin.publishTransaction(signedAnchorTx).map(txid => {
          log.info(s"anchor published with id $txid")
        }).onFailure {
          case t: Throwable => log.error(t, "cannot publish anchor tx")
        }

        log.info(s"checking their signature for our tx ${commitments.ourCommit.publishableTx}")
        val ourSig = Helpers.sign(ourParams, commitments.theirParams, commitments.anchorOutput.amount, commitments.ourCommit.publishableTx)
        val signedTx = Helpers.addSigs(ourParams, commitments.theirParams, commitments.anchorOutput.amount, commitments.ourCommit.publishableTx, ourSig, theirSigForUs)
        Helpers.checksig(ourParams, commitments.theirParams, commitments.anchorOutput, signedTx).get
    }

    def waitingForSignatureRequest(theirRequest: OpenRequest, fundingInputs: FundingInputs): Receive = {
      case SignatureRequest(theirSigForUs) =>
        val balance1 = balance - fee
        val theirBalance1 = theirRequest.balance - fee
        val anchorAmount = theirBalance1 + balance1
        val anchorOutput = TxOut(amount = anchorAmount, publicKeyScript = Scripts.anchorPubkeyScript(ourParams.commitPubKey, theirRequest.commitKey))

        val tx = Transaction(version = 2,
          txIn = fundingInputs.input :: theirRequest.input :: Nil,
          txOut = anchorOutput :: Nil,
          lockTime = 0)

        val anchorTx = Scripts.permuteInputs(Scripts.permuteOutputs(tx))
        val index = anchorTx.txOut.indexOf(anchorOutput)
        log.info(s"creating anchor tx ${anchorTx.txid}")
        val theirParams = TheirChannelParams(theirRequest.delay, theirRequest.commitKey, theirRequest.finalKey, Some(1), 100)
        val theirSpec = CommitmentSpec(Set.empty[Htlc], feeRate = 0, amount_them_msat = balance1.amount * 1000, amount_us_msat = theirBalance1.amount * 1000)
        val theirTx = makeTheirTx(ourParams, theirParams, TxIn(OutPoint(anchorTx, index), Array.emptyByteArray, 0xffffffffL) :: Nil, Hash.Zeroes, theirSpec)
        val ourSigForThem = sign(ourParams, theirParams, anchorOutput.amount, theirTx)
        val theirRevocationHash = Hash.Zeroes
        val theirNextRevocationHash = Hash.Zeroes

        val ourSpec = CommitmentSpec(Set.empty[Htlc], feeRate = 0, amount_them_msat = theirBalance1.amount * 1000, amount_us_msat = balance1.amount * 1000)
        val ourRevocationHash = Hash.Zeroes
        val ourTx = makeOurTx(ourParams, theirParams, TxIn(OutPoint(anchorTx, index), Array.emptyByteArray, 0xffffffffL) :: Nil, ourRevocationHash, ourSpec)

        val ourSig = Helpers.sign(ourParams, theirParams, anchorOutput.amount, ourTx)
        val signedTx = Helpers.addSigs(ourParams, theirParams, anchorOutput.amount, ourTx, ourSig, theirSigForUs)
        Helpers.checksig(ourParams, theirParams, anchorOutput, signedTx).get

        val commitments = Commitments(ourParams, theirParams,
          OurCommit(0, ourSpec, ourTx), TheirCommit(0, theirSpec, theirTx.txid, theirRevocationHash),
          fr.acinq.eclair.channel.OurChanges(Nil, Nil, Nil), fr.acinq.eclair.channel.TheirChanges(Nil, Nil), 0L,
          Right(theirNextRevocationHash), anchorOutput, ShaChain.init, new BasicTxDb)

        val ourInputIndex = anchorTx.txIn.indexOf(fundingInputs.input)
        val fundingSig = Transaction.signInput(anchorTx, ourInputIndex, Script.pay2pkh(ourParams.commitPubKey), SIGHASH_ANYONECANPAY | SIGHASH_ALL, fundingInputs.amount, 1, ourParams.commitPrivKey)

        sender ! SignatureResponse(fundingSig, ourSigForThem)
    }
  }

  val a = system.actorOf(Props(new Channel(Alice.channelParams)), "Alice")
  val b = system.actorOf(Props(new Channel(Bob.channelParams)), "Bob")

  a ! ('connect, 42000 satoshi, b)
}
