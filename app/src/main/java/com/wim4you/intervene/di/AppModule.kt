package com.wim4you.intervene.di

import android.content.Context
import com.wim4you.intervene.dao.AppDataBase
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.dao.DestinationHistoryDao
import com.wim4you.intervene.dao.PersonDataDao
import com.wim4you.intervene.dao.VigilanteDataDao
import com.wim4you.intervene.repository.DestinationHistoryRepository
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.repository.VigilanteDataRepository
import com.wim4you.intervene.route.DirectionsApiClient
import com.wim4you.intervene.route.RouteRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDataBase {
        return DatabaseProvider.getDatabase(context)
    }

    @Provides
    fun providePersonDataDao(database: AppDataBase): PersonDataDao = database.personDataDao()

    @Provides
    fun provideVigilanteDataDao(database: AppDataBase): VigilanteDataDao = database.vigilanteDataDao()

    @Provides
    fun provideDestinationHistoryDao(database: AppDataBase): DestinationHistoryDao =
        database.destinationHistoryDao()

    @Provides
    @Singleton
    fun providePersonDataRepository(dao: PersonDataDao): PersonDataRepository =
        PersonDataRepository(dao)

    @Provides
    @Singleton
    fun provideVigilanteDataRepository(dao: VigilanteDataDao): VigilanteDataRepository =
        VigilanteDataRepository(dao)

    @Provides
    @Singleton
    fun provideDestinationHistoryRepository(dao: DestinationHistoryDao): DestinationHistoryRepository =
        DestinationHistoryRepository(dao)

    @Provides
    @Singleton
    fun provideDirectionsApiClient(): DirectionsApiClient = DirectionsApiClient()

    @Provides
    @Singleton
    fun provideRouteRepository(
        @ApplicationContext context: Context,
        directionsApiClient: DirectionsApiClient,
    ): RouteRepository = RouteRepository(context, directionsApiClient)
}
