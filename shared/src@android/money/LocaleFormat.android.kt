package app.servicecalendar.money

import java.text.DecimalFormatSymbols

actual fun localeDecimalSeparator(): Char = DecimalFormatSymbols.getInstance().decimalSeparator
