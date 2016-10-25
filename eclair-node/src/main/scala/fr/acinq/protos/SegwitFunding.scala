package fr.acinq.protos

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.ExtendedBitcoinClient.{FundTransactionResponse, SignTransactionResponse}
import fr.acinq.eclair.channel.Scripts

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object SegwitFunding extends App {
  val (Base58.Prefix.SecretKeyTestnet, priv1) = Base58Check.decode("cV5oyXUgySSMcUvKNdKtuYg4t4NTaxkwYrrocgsJZuYac2ogEdZX")
  val (Base58.Prefix.SecretKeyTestnet, priv2) = Base58Check.decode("cNcttRx29WcftwAwKQESCQX8ZMTVykb1s5Zjpse4D6QCzwKPEL5H")

  val system = ActorSystem("mySystem")
  val config = ConfigFactory.load()
  val bitcoin = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(
    user = config.getString("eclair.bitcoind.rpcuser"),
    password = config.getString("eclair.bitcoind.rpcpassword"),
    host = config.getString("eclair.bitcoind.host"),
    port = config.getInt("eclair.bitcoind.rpcport")))

  case class FundingInputs(publishTx: Transaction, inputs: Seq[TxIn], changeOutputs: Seq[TxOut])

  def fundSegwitTx(amount: Satoshi, priv: BinaryData): Future[FundingInputs] = {
    val pub = Crypto.publicKeyFromPrivateKey(priv)
    val script: BinaryData = Script.write(Script.pay2sh(Script.pay2wpkh(pub)))
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, script) :: Nil, lockTime = 0)
    val future = for {
      FundTransactionResponse(tx1, pos, fee) <- bitcoin.fundTransaction(tx)
      SignTransactionResponse(tx2, true) <- bitcoin.signTransaction(tx1)
      pos = tx2.txOut.indexWhere(_.publicKeyScript == script)
      _ = assert(pos != -1)
      changeOutputs = tx2.txOut.filterNot(_.publicKeyScript == script)
      inputs = TxIn(OutPoint(tx2, pos), OP_PUSHDATA(Script.write(Script.pay2wpkh(pub))) :: Nil, TxIn.SEQUENCE_FINAL) :: Nil
    } yield FundingInputs(tx2, inputs, changeOutputs)

    future
  }

  def checkSegwitTx(tx: Transaction, outputIndex: Int, amount: Satoshi, priv: BinaryData): Unit = {
    val pub: BinaryData = Crypto.publicKeyFromPrivateKey(priv)
    val tx1 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, outputIndex), OP_PUSHDATA(Script.write(Script.pay2wpkh(pub))) :: Nil, TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(amount, Script.pay2wpkh(pub)) :: Nil,
      lockTime = 0)
    val sig: BinaryData = Transaction.signInput(tx1, 0, Script.pay2pkh(pub), SIGHASH_ANYONECANPAY | SIGHASH_ALL, amount, 1, priv)
    val tx2 = tx1.updateWitness(0, ScriptWitness(sig :: pub :: Nil))

    Transaction.correctlySpends(tx2, tx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  val future = for {
    FundingInputs(tx1, inputs1, outputs1) <- fundSegwitTx(10000 satoshi, priv1)
    pub1 = Crypto.publicKeyFromPrivateKey(priv1): BinaryData
    FundingInputs(tx2, inputs2, outputs2) <- fundSegwitTx(20000 satoshi, priv2)
    pub2 = Crypto.publicKeyFromPrivateKey(priv2): BinaryData
    anchorOutput = TxOut(30000 satoshi, Scripts.anchorPubkeyScript(pub1, pub2))
    anchorTx = Transaction(
      version = 2,
      txIn = inputs1 ++ inputs2,
      txOut = anchorOutput +: (outputs1 ++ outputs2),
      lockTime = 0)
    _ = println(anchorTx.hash)
    sig1 = Transaction.signInput(anchorTx, 0, Script.pay2pkh(pub1), SIGHASH_ANYONECANPAY | SIGHASH_ALL, 10000 satoshi, 1, priv1): BinaryData
    sig2 = Transaction.signInput(anchorTx, 1, Script.pay2pkh(pub2), SIGHASH_ANYONECANPAY | SIGHASH_ALL, 20000 satoshi, 1, priv2): BinaryData
    signedAnchorTx = anchorTx
      .updateWitness(0, ScriptWitness(sig1 :: pub1 :: Nil))
      .updateWitness(1, ScriptWitness(sig2 :: pub2 :: Nil))
    _ = Transaction.correctlySpends(signedAnchorTx, tx1 :: tx2 :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    _ = println(signedAnchorTx.hash)
  } yield signedAnchorTx

  val result = Await.result(future, 1000 seconds)

  println(result)
}
