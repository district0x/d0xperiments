BUILD_DIR=./contracts/build/

solc: contracts/src/FactsDb.sol
	solc --overwrite --bin --abi contracts/src/FactsDb.sol -o $(BUILD_DIR)
