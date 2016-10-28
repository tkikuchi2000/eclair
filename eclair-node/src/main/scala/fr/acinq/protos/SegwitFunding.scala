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
  val pub1 = Crypto.publicKeyFromPrivateKey(priv1): BinaryData
  val pub2 = Crypto.publicKeyFromPrivateKey(priv2): BinaryData
  val config = ConfigFactory.load()
  val bitcoin = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(
    user = config.getString("eclair.bitcoind.rpcuser"),
    password = config.getString("eclair.bitcoind.rpcpassword"),
    host = config.getString("eclair.bitcoind.host"),
    port = config.getInt("eclair.bitcoind.rpcport")))
  val count = Await.result(bitcoin.getBlockCount, 5 seconds)
  println(count)

  val system = ActorSystem("mySystem")

  case class FundingInputs(amount: Satoshi, publishTx: Transaction, input: TxIn, changeOutputs: Seq[TxOut])

  /**
    *
    * @param bitcoin bitcoin client
    * @param amount  amount to fund
    * @param pub     public key to send the funds to. The pubkey script will be p2sh(p2wpkh(pub))
    * @return a FundingInputs instance that contains:
    *         a tx to be published
    *         inputs that spends that tx
    *         change outputs
    */
  def fundSegwitTx(bitcoin: ExtendedBitcoinClient)(amount: Satoshi, pub: BinaryData): Future[FundingInputs] = {
    // send to p2sh(p2wpkh(pub))
    val script: BinaryData = Script.write(Script.pay2sh(Script.pay2wpkh(pub)))
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, script) :: Nil, lockTime = 0)
    val future = for {
      FundTransactionResponse(tx1, pos, fee) <- bitcoin.fundTransaction(tx)
      SignTransactionResponse(tx2, true) <- bitcoin.signTransaction(tx1)
      pos = tx2.txOut.indexWhere(_.publicKeyScript == script)
      _ = assert(pos != -1)
      changeOutputs = tx2.txOut.filterNot(_.publicKeyScript == script)
      // we spend from a p2sh tx: the sig script is the
      input = TxIn(OutPoint(tx2, pos), OP_PUSHDATA(Script.write(Script.pay2wpkh(pub))) :: Nil, TxIn.SEQUENCE_FINAL)
    } yield FundingInputs(amount, tx2, input, changeOutputs)

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

  def signOurInput(tx: Transaction, input: TxIn, priv: BinaryData, amount: Satoshi): Transaction = {
    var tx1 = tx
    val pub: BinaryData = Crypto.publicKeyFromPrivateKey(priv)
    for (i <- 0 until tx1.txIn.size) {
      if (input == tx1.txIn(i)) {
        val sig = Transaction.signInput(tx1, i, Script.pay2pkh(pub), SIGHASH_ANYONECANPAY | SIGHASH_ALL, amount, 1, priv): BinaryData
        tx1 = tx1.updateWitness(i, ScriptWitness(sig :: pub :: Nil))
      }
    }
    tx1
  }

  val future = for {
    FundingInputs(amount1, tx1, input1, outputs1) <- fundSegwitTx(bitcoin)(10000 satoshi, pub1)
    FundingInputs(amount2, tx2, input2, outputs2) <- fundSegwitTx(bitcoin)(20000 satoshi, pub2)
    anchorOutput = TxOut(30000 satoshi, Scripts.anchorPubkeyScript(pub1, pub2))
    anchorTx = Transaction(
      version = 2,
      txIn = input1 :: input2 :: Nil,
      txOut = anchorOutput +: (outputs1 ++ outputs2),
      lockTime = 0)
    _ = println(s"unsigned anchor: ${anchorTx.hash}")
    anchorTx1 = signOurInput(anchorTx, input1, priv1, 10000 satoshi)
    anchorTx2 = signOurInput(anchorTx1, input2, priv2, 20000 satoshi)
    sig1 = Transaction.signInput(anchorTx, 0, Script.pay2pkh(pub1), SIGHASH_ANYONECANPAY | SIGHASH_ALL, 10000 satoshi, 1, priv1): BinaryData
    sig2 = Transaction.signInput(anchorTx, 1, Script.pay2pkh(pub2), SIGHASH_ANYONECANPAY | SIGHASH_ALL, 20000 satoshi, 1, priv2): BinaryData
    signedAnchorTx = anchorTx
      .updateWitness(0, ScriptWitness(sig1 :: pub1 :: Nil))
      .updateWitness(1, ScriptWitness(sig2 :: pub2 :: Nil))
    _ = Transaction.correctlySpends(signedAnchorTx, tx1 :: tx2 :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    _ = println(s"signed anchor: ${signedAnchorTx.hash}")
    _ = assert(anchorTx.hash == signedAnchorTx.hash)
  } yield signedAnchorTx

  val result = Await.result(future, 1000 seconds)

  println(result)
  system.terminate().map(_ => println("done"))
}
