/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Niall Smyth
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.

 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package de.felixschulze.gradle

import com.android.build.gradle.api.ApplicationVariant
import de.felixschulze.gradle.internal.ProgressLoggerWrapper
import de.felixschulze.gradle.util.FileHelper
import de.felixschulze.gradle.util.ProgressHttpEntityWrapper
import de.felixschulze.teamcity.TeamCityProgressType
import de.felixschulze.teamcity.TeamCityStatusMessageHelper
import groovy.json.JsonSlurper
import org.apache.commons.io.FilenameUtils
import org.apache.http.Consts
import org.apache.http.HttpHost
import org.apache.http.HttpResponse
import org.apache.http.HttpStatus
import org.apache.http.client.HttpClient
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.entity.mime.content.FileBody
import org.apache.http.entity.mime.content.StringBody
import org.apache.http.impl.client.HttpClientBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.Nullable
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.TaskAction

/**
 * Last version notes task for plugin
 */
class HockeyAppLastVersionNotesTask extends DefaultTask {

    String variantName
    HockeyAppPluginExtension hockeyApp
    Object versionsResponse = null

    HockeyAppLastVersionNotesTask() {
        super()
        this.description = 'Gets the notes from the last version on HockeyApp'
    }

    @TaskAction
    def getNotes() throws IOException, IllegalStateException {

        hockeyApp = project.hockeyapp

        if (!getApiToken()) {
            throw new IllegalArgumentException("Cannot upload to HockeyApp because API Token is missing")
        }

        String appId = null
        if (hockeyApp.variantToApplicationId) {
            appId = hockeyApp.variantToApplicationId[variantName]
        }

        if (!appId) {
            throw new IllegalArgumentException("Could not resolve app ID for variant: ${variantName} in the variantToApplicationId map.")
        }

        return getNotesForAppId(appId)
    }

    def String getNotesForAppId(String appId) throws IllegalStateException {

        ProgressLoggerWrapper progressLogger = new ProgressLoggerWrapper(project, "Get notes from last version on Hockey App")

        progressLogger.started()

        RequestConfig.Builder requestBuilder = RequestConfig.custom()
        requestBuilder = requestBuilder.setConnectTimeout(hockeyApp.timeout)
        requestBuilder = requestBuilder.setConnectionRequestTimeout(hockeyApp.timeout)

        String proxyHost = System.getProperty("http.proxyHost", "")
        int proxyPort = System.getProperty("http.proxyPort", "0") as int
        if (proxyHost.length() > 0 && proxyPort > 0) {
            logger.lifecycle("Using proxy: " + proxyHost + ":" + proxyPort)
            HttpHost proxy = new HttpHost(proxyHost, proxyPort)
            requestBuilder = requestBuilder.setProxy(proxy)
        }

        HttpClientBuilder builder = HttpClientBuilder.create()
        builder.setDefaultRequestConfig(requestBuilder.build())
        HttpClient httpClient = builder.build()

        String apiUrl = "${hockeyApp.hockeyApiUrl}/${appId}/app_versions"

        HttpGet httpGet = new HttpGet(apiUrl)
        httpGet.addHeader("X-HockeyAppToken", getApiToken())

        logger.info("Request: " + httpGet.getRequestLine().toString())

        HttpResponse response = httpClient.execute(httpGet)

        logger.debug("Response status code: " + response.getStatusLine().getStatusCode())

        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
            parseResponseAndThrowError(response)
            return null
        } else {
            logger.lifecycle("Version details received successfully.")
            if (response.getEntity() && response.getEntity().getContentLength() > 0) {
                InputStreamReader reader = new InputStreamReader(response.getEntity().content)

                try {
                    versionsResponse = new JsonSlurper().parse(reader)
                }
                catch (Exception e) {
                    logger.error("Error while parsing JSON response: " + e.toString())
                }
                reader.close()

                if (versionsResponse) {
                    logger.debug("Version response: " + versionsResponse.toString())
                }
            }

            progressLogger.completed()

            return versionsResponse?.app_versions?.first()?.notes
        }
    }

    private void parseResponseAndThrowError(HttpResponse response) throws IllegalStateException {
        if (response.getEntity()?.getContentLength() > 0) {
            logger.debug("Response Content-Type: " + response.getFirstHeader("Content-type").getValue())
            InputStreamReader reader = new InputStreamReader(response.getEntity().content)

            try {
                versionsResponse = new JsonSlurper().parse(reader)
            } catch (Exception e) {
                logger.debug("Error while parsing JSON response: " + e.toString())
            }
            reader.close()

            if (versionsResponse) {
                logger.debug("Response: " + versionsResponse.toString())

                if (versionsResponse.status && versionsResponse.status == "error" && versionsResponse.message) {
                    logger.error("Error response from HockeyApp: " + versionsResponse.message.toString())
                    throw new IllegalStateException("Version request failed: " + versionsResponse.message.toString() + " - Status: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase())
                }
                if (versionsResponse.errors?.credentials) {
                    if (versionsResponse.errors.credentials instanceof ArrayList) {
                        ArrayList credentialsError = versionsResponse.errors.credentials
                        if (!credentialsError.isEmpty()) {
                            logger.error(credentialsError.get(0).toString())
                            throw new IllegalStateException(credentialsError.get(0).toString())
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("Version request failed: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase())
    }

    private String getApiToken() {
        String apiToken = hockeyApp.apiToken
        if (hockeyApp.variantToApiToken) {
            if (hockeyApp.variantToApiToken[variantName]) {
                apiToken = hockeyApp.variantToApiToken[variantName]
            }
        }
        return apiToken
    }
}
