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

  def fundSegwitTx(amount: Satoshi, priv: BinaryData): Future[(Transaction, Int)] = {
    val pub = Crypto.publicKeyFromPrivateKey(priv)
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, Script.pay2sh(Script.pay2wpkh(pub))) :: Nil, lockTime = 0)
    val future = for {
      FundTransactionResponse(tx1, pos, fee) <- bitcoin.fundTransaction(tx)
      SignTransactionResponse(tx2, true) <- bitcoin.signTransaction(tx1)
      pos = tx2.txOut.indexWhere(_.publicKeyScript == BinaryData(Script.write(Script.pay2sh(Script.pay2wpkh(pub)))))
      _ = assert(pos != -1)
    } yield (tx2, pos)

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
    (tx1, pos1) <- fundSegwitTx(10000 satoshi, priv1)
    pub1 = Crypto.publicKeyFromPrivateKey(priv1): BinaryData
    change1 = tx1.txOut.filterNot(_ == tx1.txOut(pos1))
    (tx2, pos2) <- fundSegwitTx(20000 satoshi, priv2)
    pub2 = Crypto.publicKeyFromPrivateKey(priv2): BinaryData
    change2 = tx2.txOut.filterNot(_ == tx2.txOut(pos2))
    anchorOutput = TxOut(30000 satoshi, Scripts.anchorPubkeyScript(pub1, pub2))
    anchorTx = Transaction(
      version = 2,
      txIn = Seq(
        TxIn(OutPoint(tx1, pos1), OP_PUSHDATA(Script.write(Script.pay2wpkh(pub1))) :: Nil, TxIn.SEQUENCE_FINAL),
        TxIn(OutPoint(tx2, pos2), OP_PUSHDATA(Script.write(Script.pay2wpkh(pub2))) :: Nil, TxIn.SEQUENCE_FINAL)
      ),
      txOut = anchorOutput +: (change1 ++ change2),
      lockTime = 0)
    _ = println(anchorTx.hash)
  } yield checkSegwitTx(tx1, pos1, 10000 satoshi, priv1)

  val result = Await.result(future, 1000 seconds)
  println(result)
}
