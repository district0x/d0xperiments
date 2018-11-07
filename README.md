# d0xperiments (WIP)

Some experimets around possible d0x architecture

<img src="/docs/arch.png?raw=true"/>

## FactsDB solidity contract

The main ideas behind FactsDB contract is to have one contract in your dApp where its emited events are going to form
your dApp database.
FactsDB uses [datoms](https://docs.datomic.com/cloud/whatis/data-model.html) as it's data store format.

It basicly consists of representing all your data as facts in the form of [entity attribute value add-or-rm]

### How to use FactsDB.sol

```solidity
import "FactsDb.sol";

contract SomeContract{
    FactsDb factsDb = FactsDb(0xFactsDBContractAddressHere);

    function addMySelf(string name, uint age, bytes ipfsPhotoHash){
         // whateever contract stuff here

         factsDb.transactString(msg.sender, "person/name", name);
         factsDb.transactUInt(msg.sender, "person/age", age);
         factsDb.transactBytes(msg.sender, "person/ipfs-photo-hash", ipfsPhotoHash);
    }
}
```

## Indexing facts in dApp UI

### Installing/Synchronizing facts in the browser

```clojure
(ns d0xperiments.example.dapp
  (:require [datascript.core :as d]
            [re-posh.core :as re-posh]
            [d0xperiments.browser-installer :as installer])
  (:require-macros [d0xperiments.utils :refer [slurpf]]))

(defonce db-conn (atom nil))
(def datascript-schema (-> (slurpf "./example-src/d0xperiments/example/db_schema.edn")
                           cljs.reader/read-string ))

(defn ^:export init []
  (let [dc (d/create-conn datascript-schema)]
    (reset! db-conn conn)
    ;; (re-posh/connect! conn) ;; if you are using re-posh
    (mount-root)
    (installer/install {:provider-url "ws://localhost:8549/"
                        :preindexer-url "http://localhost:1234"
                        :facts-db-address "0x360b6d00457775267aa3e3ef695583c675318c05"
                        :progress-cb (fn [{:keys [state] :as progress}] )
                        :ds-conn dc})))
```

### How does it works?

## Snapshots / Preindexer

### Building and running

```bash
# Build preindexer.js

clj -A:build-preindexer

# Run a instance

node preindexer.js --address 0x360b6d00457775267aa3e3ef695583c675318c05      \
                   --port 1234                                               \
                   --schema ./example-src/d0xperiments/example/db_schema.edn \
                   --rpc localhost:8549
```

### How does it work?

When it runs it starts the same syncer process started by the ui installer, it download all past and new events from FactsDB contract and indexes them
in a datascript db.
It then starts a HTTP server which exposes to endpoints /db and /datoms.

A GET to /db endpoint will respond with a edn serialized, gzip compressed version of the current db state.

A POST to /datoms will request the datoms needed in order to fullfill a collection of pulls and datalog queries.

```clojure
POST http://localhost:1234/datoms
Content-type: application/edn

{:datoms-for [{:type    :pull
              :pattern  [*]
              :ids      [1.3254459306746723e+48]}]}
```

will return something like :

```clojure
{:datoms
 #{[1.3254459306746723e+48 :meme/token-id-start "6079"]
   [1.3254459306746723e+48 :meme/token-id-end "6080"]
   [1.3254459306746723e+48 :meme/total-supply "1"]
   [1.3254459306746723e+48 :meme/total-minted "1"]
   [1.3254459306746723e+48 :meme/title "T dank"]
   [1.3254459306746723e+48 :reg-entry/deposit "1000000000000000000"]
   [1.3254459306746723e+48 :reg-entry/challenge 7901]
   [1.3254459306746723e+48 :reg-entry/challenge-period-end "1541178208"]
   [1.3254459306746723e+48 :meme/image-hash "0x516d5476654479726234544a34364a697556684b6e42735544446b4e346d58456848725167517874355238546177"]
   [1.3254459306746723e+48 :reg-entry/address "0xE82b0D159237fbFA12f4F66B3BD4e992E9CCB2Cc"]}}
```

## Creating a DApp

## Dev workflows

## Page load optimizations

Installer `:pre-fetch-datoms`

## Benchmarks

For 9000 blocks (61230 facts)

|         | Full install | Snapshot | IndexedDB |
|---------|--------------|----------|-----------|
| Desktop | ~26s         | ~5s      | ~4        |
| Mobile  |              | ~14s     | ~10s      |

## Event cost increase (transfered to users)

```solidity

event MemeConstructedEvent(address registryEntry, uint version, address creator, bytes metaHash, uint totalSupply, uint deposit, uint challengePeriodEnd);
//    375 +                32                     32            32               60              32                32            32                         = 627 = $0.001 at 7.1gwei

transactAddress(1, "reg-entry/address", xxxx)               //   375 + 32 + 17 + 32  = 456
transactUInt(1, "reg-entry/version", xxxx)                  //   375 + 32 + 17 + 32  = 456
transactAddress(1, "reg-entry/creator", xxxx)               //   375 + 32 + 17 + 32  = 456
transactString(1, "reg-entry/meta-hash", xxxx)              //   375 + 32 + 19 + 60  = 458
transactUInt(1, "reg-entry/total-supply", xxxx)             //   375 + 32 + 22 + 32  = 461
transactUInt(1, "reg-entry/deposit", xxxx)                  //   375 + 32 + 17 + 32  = 456
transactUInt(1, "reg-entry/challenge-period-end", xxxx)     //   375 + 32 + 20 + 32  = 459
                                                            //                       = 3202 = $0.005 at 7.1gwei
```

## Other ideas

### On IPFS integration

Facts defined as :ipfs/meta can be automatically grabbed by the indexer, which can retrieve data. If meta is stored as a json collection of datoms, then
they can be automatically incorporated inside datascript db, adding the facts to the same entity id the ipfs/meta hash was on.

### On being able to use the app without full sync

Use the /datoms endpoint to retrieve datoms for pull and qs, and partially synchronize browser db.
