package android.emu6502

import android.emu6502.instructions.Instruction
import android.emu6502.instructions.Opcodes
import java.util.regex.Pattern

class Assembler(private var labels: Labels,
                private var memory: Memory,
                private var symbols: Symbols) {

  private var defaultCodePC = 0
  private var codeLen = 0
  private var codeAssembledOK = false
  private var BOOTSTRAP_ADDRESS = 0x600

  fun assembleCode(lines: Array<String>): Boolean {
    lines.forEach { line ->
      if (!assembleLine(line)) {
        return false
      }
    }
    return true
  }

  private fun assembleLine(line: String): Boolean {
    var input = line
    var command: String
    var param: String
    var addr: Int

    // Find command or label
    if (input.matches("^\\w+:".toRegex())) {
      if (line.matches("^\\w+:[\\s]*\\w+.*$".toRegex())) {
        input = input.replace("^\\w+:[\\s]*(.*)$".toRegex(), "$1")
        command = input.replace("^(\\w+).*$".toRegex(), "$1")
      } else {
        command = ""
      }
    } else {
      command = input.replace("^(\\w+).*$".toRegex(), "$1")
    }

    // Nothing to do for blank lines
    if (command.equals("")) {
      return true
    }

    command = command.toUpperCase()

    if (input.matches("^\\*\\s*=\\s*\$?[0-9a-f]*$".toRegex())) {
      // equ spotted
      param = input.replace("^\\s*\\*\\s*=\\s*".toRegex(), "")
      if (param[0].equals("$")) {
        param = param.replace("^\$".toRegex(), "")
        addr = Integer.parseInt(param, 16)
      } else {
        addr = Integer.parseInt(param, 10)
      }
      if ((addr < 0) || (addr > 0xffff)) {
        throw IllegalStateException("Unable to relocate code outside 64k memory")
      }
      defaultCodePC = addr
      return true
    }

    if (input.matches("^\\w+\\s+.*?$".toRegex())) {
      param = input.replace("^\\w+\\s+(.*?)".toRegex(), "$1")
    } else if (input.matches("^\\w+$".toRegex())) {
      param = ""
    } else {
      return false
    }

    param = param.replace("[ ]".toRegex(), "")

    if (command === "DCB") {
      return DCB(param)
    }

    val opcode = Opcodes.MAP.get(Instruction.valueOf(command))

    if (opcode != null) {
      if (checkImmediate(param, opcode[0])) {
        return true
      }
      if (checkZeroPage(param, opcode[1])) {
        return true
      }
      if (checkZeroPageX(param, opcode[2])) {
        return true
      }
      if (checkZeroPageY(param, opcode[3])) {
        return true
      }
      if (checkAbsolute(param, opcode[4])) {
        return true
      }
      if (checkAbsoluteX(param, opcode[5])) {
        return true
      }
      if (checkAbsoluteY(param, opcode[6])) {
        return true
      }
      if (checkIndirect(param, opcode[7])) {
        return true
      }
      if (checkIndirectX(param, opcode[8])) {
        return true
      }
      if (checkIndirectY(param, opcode[9])) {
        return true
      }
      if (checkSingle(param, opcode[10])) {
        return true
      }
      if (checkBranch(param, opcode[11])) {
        return true
      }
    }
    return false
  }

  private fun DCB(param: String): Boolean {
    throw UnsupportedOperationException(
        "not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  private fun checkBranch(param: String, opcode: Int): Boolean {
    throw UnsupportedOperationException(
        "not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  private fun checkAbsolute(param: String, opcode: Int): Boolean {
    throw UnsupportedOperationException(
        "not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  private fun checkIndirectY(param: String, opcode: Int): Boolean {
    throw UnsupportedOperationException(
        "not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  private fun checkIndirectX(param: String, opcode: Int): Boolean {
    throw UnsupportedOperationException(
        "not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  private fun checkIndirect(param: String, opcode: Int): Boolean {
    throw UnsupportedOperationException(
        "not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  private fun checkAbsoluteY(param: String, opcode: Int): Boolean {
    throw UnsupportedOperationException(
        "not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  private fun checkAbsoluteX(param: String, opcode: Int): Boolean {
    throw UnsupportedOperationException(
        "not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  private fun checkZeroPageY(param: String, opcode: Int): Boolean {
    throw UnsupportedOperationException(
        "not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  private fun checkZeroPageX(param: String, opcode: Int): Boolean {
    throw UnsupportedOperationException(
        "not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  private fun checkZeroPage(param: String, opcode: Int): Boolean {
    throw UnsupportedOperationException(
        "not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  private fun checkImmediate(param: String, opcode: Int): Boolean {
    val pattern = Pattern.compile("^#([\\w\$]+)$")
    val matcher = pattern.matcher(param)
    if (matcher.find()) {
      var operand = tryParseByteOperand(matcher.group(1))
      if (operand >= 0) {
        pushByte(opcode)
        pushByte(operand)
        return true
      }
    }

    // Label lo/hi
    if (param.matches("^#[<>]\\w+$".toRegex())) {
      var label = param.replace("^#[<>](\\w+)$".toRegex(), "$1")
      var hilo = param.replace("^#([<>]).*$".toRegex(), "$1")
      pushByte(opcode)
      if (labels.find(label)) {
        var addr = labels.getPC(label)
        when (hilo) {
          ">" -> {
            pushByte(addr.shr(8).and(0xFF))
          }
          "<" -> {
            pushByte(addr.and(0xFF))
          }
          else -> return false
        }
      } else {
        pushByte(0x00)
        return true
      }
    }

    return false
  }

  // Try to parse the given parameter as a byte operand.
  // Returns the (positive) value if successful, otherwise -1
  private fun tryParseByteOperand(param: String): Int {
    var value: Int = 0
    var parameter = param;

    if (parameter.matches("^\\w+$".toRegex())) {
      var lookupVal = symbols.lookup(parameter) // Substitute symbol by actual value, then proceed
      if (lookupVal != null) {
        parameter = lookupVal
      }
    }

    // Is it a hexadecimal operand?
    var pattern = Pattern.compile("^\$([0-9a-f]{1,2})$")
    var matcher = pattern.matcher(parameter)
    if (matcher.find()) {
      value = Integer.parseInt(matcher.group(1), 16)
    } else {
      // Is it a decimal operand?
      pattern = Pattern.compile("^([0-9]{1,3})$")
      matcher = pattern.matcher(parameter)
      if (matcher.find()) {
        value = Integer.parseInt(matcher.group(1), 10)
      }
    }

    // Validate range
    if (value >= 0 && value <= 0xff) {
      return value
    }
    return -1
  }

  private fun pushByte(value: Int) {
    memory.set(defaultCodePC, value.and(0xFF))
    defaultCodePC++
    codeLen++
  }

  private fun checkSingle(param: String, opcode: Int): Boolean {
    throw UnsupportedOperationException(
        "not implemented") //To change body of created functions use File | Settings | File Templates.
  }
}