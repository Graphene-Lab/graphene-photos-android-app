package com.graphenelab.photosync.di

import com.graphenelab.photosync.BuildConfig
import com.graphenelab.photosync.data.network.common.AuthInterceptor
import com.graphenelab.photosync.data.network.common.IamTokenRefreshClient
import com.graphenelab.photosync.data.network.common.ITokenRefreshClient
import com.graphenelab.photosync.data.network.payment.PaymentService
import com.graphenelab.photosync.data.network.user.CloudSpaceService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideTokenRefreshClient(): ITokenRefreshClient = IamTokenRefreshClient()

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient {
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()

        // Hand the finished client back to the interceptor, so a recovery can resend the original
        // request through a brand-new chain instead of reusing the chain that produced the 401.
        authInterceptor.clientProvider = { client }
        return client
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun providePaymentService(retrofit: Retrofit): PaymentService {
        return retrofit.create(PaymentService::class.java)
    }

    @Provides
    @Singleton
    fun provideCloudSpaceService(retrofit: Retrofit): CloudSpaceService {
        return retrofit.create(CloudSpaceService::class.java)
    }
}