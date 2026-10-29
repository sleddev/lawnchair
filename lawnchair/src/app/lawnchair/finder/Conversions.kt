package app.lawnchair.finder

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** A single "x unit = y unit" line shown in the conversion card. */
data class ConversionLine(
    val from: String,
    val to: String,
    // Plain result value, copied to the clipboard when the line is picked.
    val value: String,
)

data class Conversion(
    val title: String,
    val lines: List<ConversionLine>,
    val footnote: String? = null,
)

/**
 * Parses queries like "5 km", "70f", "10 kg to lb", "100 usd in eur", "$20" or "€15 to yen" and
 * converts them, like Spotlight's unit and currency conversions. Amounts can also be combined
 * with math, like "12 eur + 34 usd to huf" or "(5 ft + 3 in) * 2 to cm".
 */
object Conversions {

    private enum class Category { LENGTH, MASS, VOLUME, TEMPERATURE, SPEED, AREA, DATA, TIME }

    private class Unit(
        val symbol: String,
        val category: Category,
        // Value of one unit in the base unit of its category. Temperature uses toBase/fromBase.
        val factor: Double,
        val aliases: List<String>,
        // Units shown when the query has no target unit.
        val defaults: List<String> = emptyList(),
    )

    private val units = listOf(
        // Length, base meter
        Unit("mm", Category.LENGTH, 0.001, listOf("mm", "millimeter", "millimeters", "millimetre", "millimetres"), listOf("in")),
        Unit("cm", Category.LENGTH, 0.01, listOf("cm", "centimeter", "centimeters", "centimetre", "centimetres"), listOf("in", "ft")),
        Unit("m", Category.LENGTH, 1.0, listOf("m", "meter", "meters", "metre", "metres"), listOf("ft", "yd")),
        Unit("km", Category.LENGTH, 1000.0, listOf("km", "kilometer", "kilometers", "kilometre", "kilometres", "kms"), listOf("mi")),
        Unit("in", Category.LENGTH, 0.0254, listOf("in", "inch", "inches", "\""), listOf("cm")),
        Unit("ft", Category.LENGTH, 0.3048, listOf("ft", "foot", "feet", "'"), listOf("m", "cm")),
        Unit("yd", Category.LENGTH, 0.9144, listOf("yd", "yard", "yards"), listOf("m")),
        Unit("mi", Category.LENGTH, 1609.344, listOf("mi", "mile", "miles"), listOf("km")),
        Unit("nmi", Category.LENGTH, 1852.0, listOf("nmi", "nautical mile", "nautical miles"), listOf("km", "mi")),
        // Mass, base kilogram
        Unit("mg", Category.MASS, 1e-6, listOf("mg", "milligram", "milligrams"), listOf("oz")),
        Unit("g", Category.MASS, 0.001, listOf("g", "gram", "grams", "gr"), listOf("oz")),
        Unit("kg", Category.MASS, 1.0, listOf("kg", "kilo", "kilos", "kilogram", "kilograms", "kgs"), listOf("lb")),
        Unit("t", Category.MASS, 1000.0, listOf("t", "tonne", "tonnes", "metric ton", "metric tons"), listOf("lb")),
        Unit("oz", Category.MASS, 0.028349523125, listOf("oz", "ounce", "ounces"), listOf("g")),
        Unit("lb", Category.MASS, 0.45359237, listOf("lb", "lbs", "pound", "pounds"), listOf("kg")),
        Unit("st", Category.MASS, 6.35029318, listOf("st", "stone", "stones"), listOf("kg", "lb")),
        // Volume, base liter
        Unit("mL", Category.VOLUME, 0.001, listOf("ml", "milliliter", "milliliters", "millilitre", "millilitres"), listOf("fl oz")),
        Unit("L", Category.VOLUME, 1.0, listOf("l", "liter", "liters", "litre", "litres"), listOf("gal", "qt")),
        Unit("fl oz", Category.VOLUME, 0.0295735295625, listOf("fl oz", "floz", "fluid ounce", "fluid ounces"), listOf("mL")),
        Unit("cup", Category.VOLUME, 0.2365882365, listOf("cup", "cups"), listOf("mL")),
        Unit("pt", Category.VOLUME, 0.473176473, listOf("pt", "pint", "pints"), listOf("mL", "L")),
        Unit("qt", Category.VOLUME, 0.946352946, listOf("qt", "quart", "quarts"), listOf("L")),
        Unit("gal", Category.VOLUME, 3.785411784, listOf("gal", "gallon", "gallons"), listOf("L")),
        Unit("tbsp", Category.VOLUME, 0.01478676478125, listOf("tbsp", "tablespoon", "tablespoons"), listOf("mL")),
        Unit("tsp", Category.VOLUME, 0.00492892159375, listOf("tsp", "teaspoon", "teaspoons"), listOf("mL")),
        // Temperature, converted with toBase/fromBase
        Unit("°C", Category.TEMPERATURE, 1.0, listOf("c", "°c", "celsius", "degrees celsius", "degree celsius", "deg c", "degc"), listOf("°F")),
        Unit("°F", Category.TEMPERATURE, 1.0, listOf("f", "°f", "fahrenheit", "degrees fahrenheit", "degree fahrenheit", "deg f", "degf"), listOf("°C")),
        Unit("K", Category.TEMPERATURE, 1.0, listOf("k", "kelvin", "kelvins"), listOf("°C", "°F")),
        // Speed, base m/s
        Unit("km/h", Category.SPEED, 1 / 3.6, listOf("km/h", "kmh", "kph", "kmph", "kilometers per hour", "kilometres per hour"), listOf("mph")),
        Unit("mph", Category.SPEED, 0.44704, listOf("mph", "mi/h", "miles per hour"), listOf("km/h")),
        Unit("m/s", Category.SPEED, 1.0, listOf("m/s", "mps", "meters per second", "metres per second"), listOf("km/h", "mph")),
        Unit("kn", Category.SPEED, 0.514444, listOf("kn", "knot", "knots", "kt"), listOf("km/h", "mph")),
        // Area, base m²
        Unit("m²", Category.AREA, 1.0, listOf("m2", "m²", "sqm", "sq m", "square meter", "square meters", "square metre", "square metres"), listOf("ft²")),
        Unit("km²", Category.AREA, 1e6, listOf("km2", "km²", "sq km", "square kilometer", "square kilometers"), listOf("mi²")),
        Unit("ft²", Category.AREA, 0.09290304, listOf("ft2", "ft²", "sqft", "sq ft", "square foot", "square feet"), listOf("m²")),
        Unit("mi²", Category.AREA, 2589988.110336, listOf("mi2", "mi²", "sq mi", "square mile", "square miles"), listOf("km²")),
        Unit("ac", Category.AREA, 4046.8564224, listOf("ac", "acre", "acres"), listOf("ha", "m²")),
        Unit("ha", Category.AREA, 10000.0, listOf("ha", "hectare", "hectares"), listOf("ac")),
        // Data, base byte
        Unit("KB", Category.DATA, 1e3, listOf("kb", "kilobyte", "kilobytes"), listOf("B")),
        Unit("MB", Category.DATA, 1e6, listOf("mb", "megabyte", "megabytes"), listOf("KB", "GB")),
        Unit("GB", Category.DATA, 1e9, listOf("gb", "gigabyte", "gigabytes"), listOf("MB", "TB")),
        Unit("TB", Category.DATA, 1e12, listOf("tb", "terabyte", "terabytes"), listOf("GB")),
        Unit("B", Category.DATA, 1.0, listOf("byte", "bytes"), listOf("KB")),
        // Time, base second
        Unit("s", Category.TIME, 1.0, listOf("s", "sec", "secs", "second", "seconds"), listOf("min")),
        Unit("min", Category.TIME, 60.0, listOf("min", "mins", "minute", "minutes"), listOf("h", "s")),
        Unit("h", Category.TIME, 3600.0, listOf("h", "hr", "hrs", "hour", "hours"), listOf("min")),
        Unit("d", Category.TIME, 86400.0, listOf("d", "day", "days"), listOf("h")),
        Unit("wk", Category.TIME, 604800.0, listOf("wk", "wks", "week", "weeks"), listOf("d")),
    )

    private val unitsByAlias: Map<String, Unit> = buildMap {
        units.forEach { unit -> unit.aliases.forEach { put(it, unit) } }
    }
    private val unitsBySymbol = units.associateBy { it.symbol }

    private val currencySymbols = mapOf(
        "$" to "USD", "us$" to "USD", "€" to "EUR", "£" to "GBP", "¥" to "JPY", "₹" to "INR",
        "₩" to "KRW", "₽" to "RUB", "₺" to "TRY", "₪" to "ILS", "฿" to "THB", "₫" to "VND",
        "₱" to "PHP", "r$" to "BRL", "c$" to "CAD", "ca$" to "CAD", "a$" to "AUD", "au$" to "AUD",
        "nz$" to "NZD", "hk$" to "HKD", "s$" to "SGD", "zł" to "PLN", "₴" to "UAH", "₦" to "NGN",
        "kč" to "CZK", "chf" to "CHF",
    )

    private val currencyNames = mapOf(
        "dollar" to "USD", "dollars" to "USD", "usd" to "USD", "buck" to "USD", "bucks" to "USD",
        "euro" to "EUR", "euros" to "EUR",
        "pound" to "GBP", "pounds" to "GBP", "quid" to "GBP", "sterling" to "GBP",
        "yen" to "JPY", "yuan" to "CNY", "renminbi" to "CNY", "rmb" to "CNY",
        "rupee" to "INR", "rupees" to "INR", "won" to "KRW",
        "ruble" to "RUB", "rubles" to "RUB", "rouble" to "RUB", "roubles" to "RUB",
        "franc" to "CHF", "francs" to "CHF", "lira" to "TRY", "baht" to "THB",
        "peso" to "MXN", "pesos" to "MXN", "real" to "BRL", "reais" to "BRL",
        "krona" to "SEK", "kronor" to "SEK", "krone" to "NOK", "kroner" to "NOK",
        "zloty" to "PLN", "forint" to "HUF", "rand" to "ZAR", "dirham" to "AED", "dirhams" to "AED",
        "riyal" to "SAR", "shekel" to "ILS", "shekels" to "ILS", "hryvnia" to "UAH",
        "ringgit" to "MYR", "rupiah" to "IDR", "dong" to "VND", "naira" to "NGN",
        "bitcoin" to "BTC",
    )

    // number, then optional unit, then optional "to/in/=/->" target.
    private val numberPattern = "[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?|[-+]?\\.\\d+"
    private val queryRegex = Regex(
        "^\\s*(?<prefix>[^\\d\\s.+-]{1,3})?\\s*(?<num>$numberPattern)\\s*(?<unit>[^\\d]*?)\\s*" +
            "(?:(?:to|in|into|=|->|→|as)\\s*(?<target>[^\\d]+?))?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private sealed interface Quantity {
        data class Physical(val unit: Unit) : Quantity
        data class Money(val code: String) : Quantity
    }

    private val Quantity.dimension: Any
        get() = when (this) {
            is Quantity.Physical -> unit.category
            is Quantity.Money -> MONEY
        }

    /**
     * Returns a conversion for [query], or null when it doesn't look like one. Currency
     * conversions need [rates] (units per USD); they're skipped when it's null.
     */
    fun convert(query: String, rates: Map<String, Double>?, locale: Locale = Locale.getDefault()): Conversion? {
        val match = queryRegex.matchEntire(query.trim())
            ?: return convertExpression(query, rates, locale)
        val prefix = match.groups["prefix"]?.value?.trim()?.lowercase(locale).orEmpty()
        val numberText = match.groups["num"]?.value ?: return null
        val unitText = match.groups["unit"]?.value?.trim()?.lowercase(locale).orEmpty()
        val targetText = match.groups["target"]?.value?.trim()?.lowercase(locale)
        val amount = numberText.replace(",", "").toBigDecimalOrNull() ?: return null

        val source = when {
            prefix.isNotEmpty() && unitText.isEmpty() -> currencySymbols[prefix]?.let { Quantity.Money(it) }
            prefix.isNotEmpty() -> null
            else -> parseQuantity(unitText, rates)
        } ?: return null

        val target = targetText?.let { parseQuantity(it, rates) ?: return null }

        return when (source) {
            is Quantity.Physical -> {
                if (target != null && (target !is Quantity.Physical || target.unit.category != source.unit.category)) return null
                convertUnits(amount, source.unit, (target as? Quantity.Physical)?.unit, locale)
            }

            is Quantity.Money -> {
                if (rates == null) return null
                if (target != null && target !is Quantity.Money) return null
                convertMoney(amount, source.code, (target as? Quantity.Money)?.code, rates, locale)
            }
        }
    }

    /** Whether [query] looks like it's a conversion, even if it can't be computed (yet). */
    fun isCurrencyQuery(query: String): Boolean {
        val match = queryRegex.matchEntire(query.trim())
            ?: return parseWithTarget(query, null, Locale.getDefault())?.first?.usesMoney == true
        val prefix = match.groups["prefix"]?.value?.trim()?.lowercase().orEmpty()
        val unitText = match.groups["unit"]?.value?.trim()?.lowercase().orEmpty()
        if (prefix.isNotEmpty()) return unitText.isEmpty() && prefix in currencySymbols
        return unitText in currencyNames || unitText in currencySymbols ||
            (unitText.length == 3 && unitText !in unitsByAlias && isCurrencyCode(unitText.uppercase()))
    }

    private fun isCurrencyCode(code: String) = runCatching { Currency.getInstance(code) }.isSuccess

    private fun parseQuantity(text: String, rates: Map<String, Double>?): Quantity? {
        if (text.isEmpty()) return null
        val cleaned = text.removePrefix("degrees ").removePrefix("degree ").trim()
        unitsByAlias[text]?.let { return Quantity.Physical(it) }
        unitsByAlias[cleaned]?.let { return Quantity.Physical(it) }
        currencySymbols[text]?.let { return Quantity.Money(it) }
        currencyNames[text]?.let { return Quantity.Money(it) }
        val code = text.uppercase(Locale.ROOT)
        if (code.length == 3 && (rates?.containsKey(code) == true || isCurrencyCode(code))) {
            return Quantity.Money(code)
        }
        return null
    }

    private val targetSplitRegex = Regex("(?<=\\s)(?:to|in|into|as)(?=\\s)|=|->|→", RegexOption.IGNORE_CASE)
    private val operandNumberRegex = Regex("^(?:$numberPattern)".replace("[-+]?", ""))

    // Every word that can follow (or for currencies, precede) a number, longest first so "km/h"
    // wins over "km" and "fl oz" over "fl".
    private val operandAliases: List<String> =
        (unitsByAlias.keys + currencySymbols.keys + currencyNames.keys).sortedByDescending { it.length }
    private val prefixSymbols = currencySymbols.keys.sortedByDescending { it.length }

    /** A computed amount, in the base unit of its dimension (USD for money). */
    private data class Amount(val value: Double, val dimension: Any?)

    private class Expression(
        val amount: Amount,
        // The operands' units in order, the first one is used for the default targets.
        val quantities: List<Quantity>,
        val display: String,
    ) {
        val usesMoney get() = quantities.any { it is Quantity.Money }
    }

    /** Handles queries with math in them, like "12 eur + 34 usd to huf". */
    private fun convertExpression(query: String, rates: Map<String, Double>?, locale: Locale): Conversion? {
        val (expression, target) = parseWithTarget(query, rates, locale) ?: return null
        if (expression.usesMoney && rates == null) return null
        return expressionConversion(expression, target, rates.orEmpty(), locale)
    }

    private fun parseWithTarget(query: String, rates: Map<String, Double>?, locale: Locale): Pair<Expression, Quantity?>? {
        val trimmed = query.trim().removeSuffix("=").trimEnd()
        // Try the last "to ..." first, so "5 ft + 3 in to cm" doesn't split at "in".
        for (split in targetSplitRegex.findAll(trimmed).toList().asReversed()) {
            val targetText = trimmed.substring(split.range.last + 1).trim().lowercase(locale)
            val target = parseQuantity(targetText, rates) ?: continue
            val expression = parseExpression(trimmed.substring(0, split.range.first), rates, locale) ?: continue
            return expression to target
        }
        return parseExpression(trimmed, rates, locale)?.let { it to null }
    }

    private fun expressionConversion(
        expression: Expression,
        target: Quantity?,
        rates: Map<String, Double>,
        locale: Locale,
    ): Conversion? {
        val amount = expression.amount
        if (!amount.value.isFinite()) return null
        // The result's unit, like the first "km" in "10 km + 5 mi" or "eur" in "60 usd / 3 usd * 2 eur".
        val first = expression.quantities.firstOrNull { it.dimension == amount.dimension } ?: return null
        val lines = when (first) {
            is Quantity.Money -> {
                if (target != null && target !is Quantity.Money) return null
                val codes = if (target is Quantity.Money) {
                    listOf(target.code)
                } else {
                    val local = runCatching { Currency.getInstance(locale).currencyCode }.getOrNull()
                    listOf(first.code) + listOfNotNull(local, "USD", "EUR", "GBP").distinct().filter { it != first.code }.take(2)
                }
                codes.mapNotNull { code ->
                    val result = amount.value * (rates[code] ?: return@mapNotNull null)
                    ConversionLine(expression.display, formatMoney(result, code, locale), plainNumber(result, 2))
                }
            }

            is Quantity.Physical -> {
                if (target != null && (target !is Quantity.Physical || target.unit.category != first.unit.category)) return null
                val targets = (target as? Quantity.Physical)?.let { listOf(it.unit) }
                    ?: (listOf(first.unit) + first.unit.defaults.mapNotNull { unitsBySymbol[it] })
                targets.map { unit ->
                    val result = amount.value / unit.factor
                    ConversionLine(expression.display, "${formatNumber(result, locale)} ${unit.symbol}", plainNumber(result))
                }
            }
        }
        if (lines.isEmpty()) return null
        return Conversion(title = lines.first().to, lines = lines)
    }

    /**
     * Parses +, -, *, / and parentheses over amounts like "12 eur", "$5" or "3 ft". It needs at
     * least one operator and one amount with a unit, plain math is left to the calculator. Without
     * [rates], money amounts are NaN but the expression still parses, to tell it needs rates.
     */
    private fun parseExpression(text: String, rates: Map<String, Double>?, locale: Locale): Expression? {
        val parser = ExpressionParser(text.lowercase(locale).replace('−', '-'), rates, locale)
        val amount = try {
            parser.parse()
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (parser.operators == 0 || amount.dimension == null || parser.quantities.isEmpty()) return null
        return Expression(amount, parser.quantities, parser.display.toString())
    }

    private class ExpressionParser(val text: String, val rates: Map<String, Double>?, val locale: Locale) {
        var pos = 0
        var operators = 0
        val quantities = mutableListOf<Quantity>()
        val display = StringBuilder()

        fun parse(): Amount {
            val amount = sum()
            skipSpaces()
            require(pos == text.length)
            return amount
        }

        private fun sum(): Amount {
            var left = product()
            while (true) {
                skipSpaces()
                val op = text.getOrNull(pos)?.takeIf { it == '+' || it == '-' } ?: return left
                pos++
                operators++
                display.append(" $op ")
                val right = product()
                require(left.dimension == right.dimension)
                left = Amount(if (op == '+') left.value + right.value else left.value - right.value, left.dimension)
            }
        }

        private fun product(): Amount {
            var left = factor()
            while (true) {
                skipSpaces()
                val op = text.getOrNull(pos)?.takeIf { it in "*×/÷" } ?: return left
                pos++
                operators++
                display.append(if (op == '*' || op == '×') " × " else " ÷ ")
                val right = factor()
                left = if (op == '*' || op == '×') {
                    require(left.dimension == null || right.dimension == null)
                    Amount(left.value * right.value, left.dimension ?: right.dimension)
                } else {
                    // Dividing two amounts of the same kind gives a plain ratio.
                    require(right.dimension == null || right.dimension == left.dimension)
                    Amount(left.value / right.value, if (right.dimension == null) left.dimension else null)
                }
            }
        }

        private fun factor(): Amount {
            skipSpaces()
            when (text.getOrNull(pos)) {
                '-' -> {
                    pos++
                    display.append('-')
                    val inner = factor()
                    return inner.copy(value = -inner.value)
                }

                '(' -> {
                    pos++
                    display.append('(')
                    val inner = sum()
                    skipSpaces()
                    require(text.getOrNull(pos) == ')')
                    pos++
                    display.append(')')
                    return inner
                }
            }
            return operand()
        }

        private fun operand(): Amount {
            val prefix = prefixSymbols.firstOrNull { text.startsWith(it, pos) }
            if (prefix != null) pos += prefix.length
            skipSpaces()
            val number = operandNumberRegex.find(text.substring(pos))
            requireNotNull(number)
            pos += number.value.length
            val value = number.value.replace(",", "").toDouble()

            val quantity = if (prefix != null) {
                Quantity.Money(currencySymbols.getValue(prefix))
            } else {
                unit()
            }
            if (quantity == null) {
                display.append(formatNumber(value, locale))
                return Amount(value, null)
            }
            quantities += quantity
            return when (quantity) {
                is Quantity.Money -> {
                    display.append(formatMoney(value, quantity.code, locale, withCode = false))
                    val rate = if (rates == null) Double.NaN else requireNotNull(rates[quantity.code])
                    Amount(value / rate, MONEY)
                }

                is Quantity.Physical -> {
                    // Temperatures don't add up linearly.
                    require(quantity.unit.category != Category.TEMPERATURE)
                    display.append("${formatNumber(value, locale)} ${quantity.unit.symbol}")
                    Amount(value * quantity.unit.factor, quantity.unit.category)
                }
            }
        }

        private fun unit(): Quantity? {
            val start = pos
            skipSpaces()
            val alias = operandAliases.firstOrNull { text.startsWith(it, pos) && isBoundary(pos + it.length) }
            if (alias != null) {
                pos += alias.length
                return parseQuantity(alias, rates)
            }
            val code = text.substring(pos).take(3)
            if (code.length == 3 && code.all { it.isLetter() } && isBoundary(pos + 3)) {
                parseQuantity(code, rates)?.let {
                    pos += 3
                    return it
                }
            }
            pos = start
            return null
        }

        private fun isBoundary(index: Int) = index >= text.length || text[index].isWhitespace() || text[index] in "+-*×/÷()"

        private fun skipSpaces() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }

    private const val MONEY = "money"

    private fun convertUnits(amount: BigDecimal, from: Unit, to: Unit?, locale: Locale): Conversion? {
        val targets = if (to != null) {
            if (to == from) return null
            listOf(to)
        } else {
            from.defaults.mapNotNull { unitsBySymbol[it] }
        }
        if (targets.isEmpty()) return null
        val value = amount.toDouble()
        val lines = targets.map { target ->
            val result = if (from.category == Category.TEMPERATURE) {
                fromCelsius(toCelsius(value, from.symbol), target.symbol)
            } else {
                value * from.factor / target.factor
            }
            ConversionLine(
                from = "${formatNumber(amount.toDouble(), locale)} ${from.symbol}",
                to = "${formatNumber(result, locale)} ${target.symbol}",
                value = plainNumber(result),
            )
        }
        return Conversion(title = lines.first().to, lines = lines)
    }

    private fun toCelsius(value: Double, symbol: String) = when (symbol) {
        "°F" -> (value - 32) * 5 / 9
        "K" -> value - 273.15
        else -> value
    }

    private fun fromCelsius(value: Double, symbol: String) = when (symbol) {
        "°F" -> value * 9 / 5 + 32
        "K" -> value + 273.15
        else -> value
    }

    private fun convertMoney(
        amount: BigDecimal,
        from: String,
        to: String?,
        rates: Map<String, Double>,
        locale: Locale,
    ): Conversion? {
        val fromRate = rates[from] ?: return null
        val targets = if (to != null) {
            if (to == from) return null
            listOf(to)
        } else {
            val local = runCatching { Currency.getInstance(locale).currencyCode }.getOrNull()
            listOfNotNull(local, "USD", "EUR", "GBP").distinct().filter { it != from }.take(2)
        }
        val lines = targets.mapNotNull { code ->
            val toRate = rates[code] ?: return@mapNotNull null
            val result = amount.toDouble() / fromRate * toRate
            ConversionLine(
                from = formatMoney(amount.toDouble(), from, locale),
                to = formatMoney(result, code, locale),
                value = plainNumber(result, 2),
            )
        }
        if (lines.isEmpty()) return null
        return Conversion(title = lines.first().to, lines = lines)
    }

    private fun formatMoney(value: Double, code: String, locale: Locale, withCode: Boolean = true): String {
        val currency = runCatching { Currency.getInstance(code) }.getOrNull()
            ?: return "${formatNumber(value, locale)} $code"
        val format = NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = currency
            maximumFractionDigits = currency.defaultFractionDigits.coerceAtLeast(0)
            minimumFractionDigits = currency.defaultFractionDigits.coerceAtLeast(0)
        }
        val formatted = format.format(value)
        // Ambiguous symbols like "$" get the currency code, like "$100 USD".
        val symbol = currency.getSymbol(locale)
        return if (withCode && symbol.length <= 2 && !formatted.contains(code)) "$formatted $code" else formatted
    }

    private fun formatNumber(value: Double, locale: Locale): String {
        val format = NumberFormat.getNumberInstance(locale)
        val abs = kotlin.math.abs(value)
        format.maximumFractionDigits = when {
            abs == 0.0 -> 0
            abs >= 100 -> 2
            abs >= 1 -> 3
            else -> 6
        }
        return format.format(BigDecimal(value).round(MathContext(10, RoundingMode.HALF_UP)))
    }

    private fun plainNumber(value: Double, maxFraction: Int = 6): String {
        return BigDecimal(value).setScale(maxFraction, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
