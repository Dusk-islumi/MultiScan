package com.example.multiscan.core.code128

/**
 * Code 128C 校验工具。
 *
 * Code 128C 只编码数字，且每个符号固定 2 位，因此合法内容必须满足：
 *  - 非空；
 *  - 所有字符都是 ASCII 数字 `0-9`；
 *  - 长度为偶数。
 *
 * App 中用于识别结果的格式辅助判断与记录页展示（zxing-cpp 返回的是 Code128 家族，
 * 不区分 C 子集，这里给出可判定的纯函数并配套单元测试）。
 */
object Code128CValidator {

    /** 是否满足 Code128C 的内容约束（纯数字 + 偶数长度）。 */
    fun isValid(value: String): Boolean =
        value.isNotEmpty() &&
            value.length % 2 == 0 &&
            value.all { it in '0'..'9' }

    /**
     * 返回规范化后的内容：满足 [isValid] 时原样返回，否则返回 null。
     * （不做去空格/补零等隐式修改，避免改变条码内容。）
     */
    fun normalize(value: String?): String? =
        value?.takeIf(::isValid)
}
