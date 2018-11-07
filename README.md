# d0xperiments (WIP)

Some experimets around possible d0x architecture

<img src="/docs/arch.png?raw=true"/>

## FactsDB solidity contract

## Snapshots / Preindexer

```bash
clj -Sdeps "{:deps {district0x/d0xperiments {:git/url \"https://github.com/district0x/d0xperiments\" :sha \"\"}}}" -A:build-preindexer
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
