# eclair モナコインテスト

[eclair](https://github.com/ACINQ/eclair)をmonaのmainnetで動くようにしたものです。

## 設定方法

monacoin.confの例：

    rpcuser=hoge
    rpcpassword=fuga
    rpcallowip=127.0.0.1
    rpcport=9402
    txindex=1
    zmqpubrawblock=tcp://127.0.0.1:29000
    zmqpubrawtx=tcp://127.0.0.1:29000

eclair.conf をデータディレクトリ (どこにあるかはosなどによる)に置きます。

例：

    eclair {
      bitcoind {
        rpcuser = "hoge"
        rpcpassword = "fuga"
        rpcport = 9402
        zmq = "tcp://127.0.0.1:29000"
      }
    
      api {
        binding-ip = "127.0.0.1"
        port = 8087
      }
    }

