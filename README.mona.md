# eclair モナコインテスト

[eclair](https://github.com/ACINQ/eclair)をmonaのmainnetで動くようにしたものです。

zeromqが有効化されたmonacoin coreが必要です。

## 設定方法

monacoin.confの例：

    rpcuser=hoge
    rpcpassword=fuga
    rpcallowip=127.0.0.1
    rpcport=9402
    txindex=1
    server=1
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

## テストノード

* 03aafadba1c7608a52b8defcb880e52c76d06eb36b6cb740ef94649b2a5277701e@54.201.194.99:9735

このノードにチャンネルを貼ると、10mMONA以上の資金がテストノード側にあるチャンネルが無い場合、自動的にチャンネルを貼り返してくるようにしたいが、現在うまく動いていません。

### テスト時の注意点

* 現在、一つのチャンネルに入れる最大資金は 0.16777216 mona , 一度に送金できる金額は 0.04294967'296 monaに制限されています。またノードのデフォルト設定では、0.00001 mona以下の送金は受けつけられません。

* テストは失ってもいい金額の範囲で行ってください。monaを失っても開発者は一切の責任を負いません。

## askmonaスレ

[ライトニングネットワーク実験場 - Ask Mona](http://askmona.org/4955)

