package com.miraglio.di

import com.miraglio.i18n.AndroidTexts
import com.miraglio.i18n.Texts
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Lega [Texts] all'implementazione sulle risorse Android.
 *
 * Serve a chi deve comporre testo senza essere un `@Composable`: le ViewModel
 * (messaggi di stato e di errore) e l'aggiornamento (messaggio d'invito e
 * messaggi di errore). Le schermate Compose usano `stringResource` e non
 * passano da qui.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TextsModule {

    @Binds
    @Singleton
    abstract fun bindTexts(impl: AndroidTexts): Texts
}
