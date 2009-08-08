package org.jruby.compiler.ir.operands;

import java.util.List;
import java.util.Map;

// Represents a hash { _ =>_, _ => _ .. } in ruby
//
// NOTE: This operand is only used in the initial stages of optimization.
// Further down the line, this hash could get converted to calls
// that actually build the hash
public class Hash extends Operand
{
    final public List<KeyValuePair> _pairs;

    public Hash(List<KeyValuePair> pairs) { _pairs = pairs; }

    public boolean isBlank() { return _pairs == null || _pairs.size() == 0; }

    public boolean isConstant() 
    {
       for (KeyValuePair kp: _pairs)
          if (!kp._key.isConstant() || !kp._value.isConstant())
             return false;

       return true;
    }

    public boolean isNonAtomicValue() { return true; }

    public Operand getSimplifiedOperand(Map<Operand, Operand> valueMap)
    {
        int i = 0;
        for (KeyValuePair kv: _pairs) {
           kv._key   = kv._key.getSimplifiedOperand(valueMap);
           kv._value = kv._value.getSimplifiedOperand(valueMap);
           i++;
        }

        return this;
    }
}
