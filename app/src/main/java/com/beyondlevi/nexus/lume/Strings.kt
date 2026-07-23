package com.beyondlevi.nexus.lume

/** Bilingual HUD strings (English / Português), chosen on the phone and pushed
 *  to the reader — parity with the original's phone-selected locale. */
internal object Strings {
    fun libraryTitle(lang: String) = if (lang == "pt") "Biblioteca" else "Library"
    fun emptyLibrary(lang: String) =
        if (lang == "pt") "Sem documentos. Adicione no telefone." else "No documents. Add one on the phone."
    fun libraryEmptyFooter(lang: String) =
        if (lang == "pt") "voltar sai" else "back exits"
    fun libraryFooter(lang: String) =
        if (lang == "pt") "toque abre · voltar sai" else "tap opens · back exits"
    fun readerPlayingFooter(lang: String) =
        if (lang == "pt") "toque pausa · deslize velocidade · voltar" else "tap pause · swipe speed · back"
    fun readerPausedFooter(lang: String) =
        if (lang == "pt") "toque continua · deslize frase · voltar" else "tap play · swipe sentence · back"
}
