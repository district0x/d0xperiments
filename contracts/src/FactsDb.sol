pragma solidity ^0.4.24;

contract FactsDb {
  event Fact(uint entity, string attribute, string val,  bool add);
  event Fact(uint entity, string attribute, uint val,    bool add);
  event Fact(uint entity, string attribute, address val, bool add);
  event Fact(uint entity, string attribute, bytes val, bool add);


  function transactString(uint entity, string attribute, string val) public {
    emit Fact(entity, attribute, val, true);
  }
  function transactUInt(uint entity, string attribute, uint val) public {
    emit Fact(entity, attribute, val, true);
  }
  function transactAddress(uint entity, string attribute, address val) public {
    emit Fact(entity, attribute, val, true);
  }
  function transactBytes(uint entity, string attribute, bytes val) public {
    emit Fact(entity, attribute, val, true);
  }


  function removeString(uint entity, string attribute, string val) public {
    emit Fact(entity, attribute, val, false);
  }
}
