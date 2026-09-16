package com.ant.robot.utils;

public class ModbusUtils {

    /**
     * 将int数组转换为Modbus寄存器数组
     */
    public static com.ghgande.j2mod.modbus.procimg.Register[] toRegisterArray(int[] values) {
        com.ghgande.j2mod.modbus.procimg.Register[] registers =
                new com.ghgande.j2mod.modbus.procimg.SimpleRegister[values.length];

        for (int i = 0; i < values.length; i++) {
            registers[i] = new com.ghgande.j2mod.modbus.procimg.SimpleRegister(values[i]);
        }

        return registers;
    }

    /**
     * 将Modbus寄存器数组转换为int数组
     */
    public static int[] toIntArray(com.ghgande.j2mod.modbus.procimg.InputRegister[] registers) {
        int[] values = new int[registers.length];
        for (int i = 0; i < registers.length; i++) {
            values[i] = registers[i].getValue();
        }
        return values;
    }

    /**
     * 将Modbus寄存器数组转换为byte数组
     */
    public static byte[] toByteArray(com.ghgande.j2mod.modbus.procimg.InputRegister[] registers) {
        // 创建一个字节数组，长度是寄存器数量的2倍，因为每个寄存器转换为2个字节
        byte[] values = new byte[registers.length * 2];
        for (int i = 0; i < registers.length; i++) {
            // 将每个寄存器的值转换为2个字节，并存入字节数组
            int registerValue = registers[i].getValue();
            values[i * 2] = (byte) (registerValue >> 8);  // 高字节
            values[i * 2 + 1] = (byte) (registerValue & 0xFF);  // 低字节
        }
        return values;
    }

}
