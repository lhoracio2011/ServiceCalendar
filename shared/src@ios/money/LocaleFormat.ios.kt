package app.servicecalendar.money

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleDecimalSeparator
import platform.Foundation.currentLocale

actual fun localeDecimalSeparator(): Char =
    (NSLocale.currentLocale.objectForKey(NSLocaleDecimalSeparator) as? String)?.firstOrNull() ?: ','
