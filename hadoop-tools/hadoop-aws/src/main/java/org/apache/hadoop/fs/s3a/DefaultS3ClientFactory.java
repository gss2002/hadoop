/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.s3a;

import java.io.IOException;
import java.net.URI;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Builder;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.s3.internal.ServiceUtils;
import com.amazonaws.util.AwsHostNameUtils;
import com.amazonaws.util.RuntimeHttpUtils;

import com.google.common.annotations.VisibleForTesting;

import org.slf4j.Logger;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.commons.lang3.StringUtils;

import static org.apache.hadoop.fs.s3a.Constants.AWS_REGION;
import static org.apache.hadoop.fs.s3a.Constants.AWS_S3_CENTRAL_REGION;
import static org.apache.hadoop.fs.s3a.Constants.ENDPOINT;
import static org.apache.hadoop.fs.s3a.Constants.PATH_STYLE_ACCESS;

/**
 * The default {@link S3ClientFactory} implementation.
 * This which calls the AWS SDK to configure and create an
 * {@link AmazonS3Client} that communicates with the S3 service.
 */
public class DefaultS3ClientFactory extends Configured
    implements S3ClientFactory {

  protected static final Logger LOG = S3AFileSystem.LOG;
      
  private static final String SDK_REGION_CHAIN_IN_USE =
	      "S3A filesystem client is using"
	          + " the SDK region resolution chain.";

  private static final String S3_SERVICE_NAME = "s3";

  @Override
  public AmazonS3 createS3Client(URI name,
      final String bucket,
      final AWSCredentialsProvider credentials) throws IOException {
    Configuration conf = getConf();
    final ClientConfiguration awsConf = S3AUtils.createAwsConf(getConf(), bucket);
    return configureAmazonS3Client(credentials, conf, awsConf);
  }

  /**
   * Wrapper around constructor for {@link AmazonS3} client.
   * Override this to provide an extended version of the client
   * @param credentials credentials to use
   * @param awsConf  AWS configuration
   * @return  new AmazonS3 client
   */
  protected AmazonS3 newAmazonS3Client(
      AWSCredentialsProvider credentials, ClientConfiguration awsConf) {
    return new AmazonS3Client(credentials, awsConf);
  }

  /**
   * Configure S3 client from the Hadoop configuration.
   *
   * This includes: endpoint, Path Access and possibly other
   * options.
   *
   * @param conf Hadoop configuration
   * @return S3 client
   * @throws IllegalArgumentException if misconfigured
   */
  private static AmazonS3 configureAmazonS3Client(AWSCredentialsProvider credentials,
      Configuration conf, ClientConfiguration awsConf)
      throws IllegalArgumentException {
	AmazonS3ClientBuilder builder = AmazonS3Client.builder();
	builder.withCredentials(credentials);
	builder.withClientConfiguration(awsConf);
	builder.withPathStyleAccessEnabled(conf.getBoolean(PATH_STYLE_ACCESS, false));
	AwsClientBuilder.EndpointConfiguration epr = createEndpointConfiguration(conf.getTrimmed(ENDPOINT, ""),
	      awsConf, conf.getTrimmed(AWS_REGION));
	      configureEndpoint(builder, epr, conf);
	      final AmazonS3 client = builder.build();
	return client;	  
  }

  /**
  * A method to configure endpoint and Region for an AmazonS3Builder.
  *
  * @param builder Instance of AmazonS3Builder used.
  * @param epr     EndpointConfiguration used to set in builder.
  */
  private static void configureEndpoint(
      AmazonS3Builder builder,
      AmazonS3Builder.EndpointConfiguration epr, Configuration conf) {
    if (epr != null) {
      // an endpoint binding was constructed: use it.
      builder.withEndpointConfiguration(epr);
      } else {
        // no idea what the endpoint is, so tell the SDK
        // to work it out at the cost of an extra HEAD request
        builder.withForceGlobalBucketAccessEnabled(true);
        // HADOOP-17771 force set the region so the build process doesn't halt.
        String region = conf.getTrimmed(AWS_REGION, AWS_S3_CENTRAL_REGION);
        LOG.debug("fs.s3a.endpoint.region=\"{}\"", region);
        if (!region.isEmpty()) {
          // there's either an explicit region or we have fallen back
          // to the central one.
          LOG.debug("Using default endpoint; setting region to {}", region);
          builder.setRegion(region);
        } else {
          // no region.
          // allow this if people really want it; it is OK to rely on this
          // when deployed in EC2.
          LOG.warn(SDK_REGION_CHAIN_IN_USE);
          LOG.debug(SDK_REGION_CHAIN_IN_USE);
        }
      }
    }
    /**
     * Given an endpoint string, return an endpoint config, or null, if none
     * is needed.
     * <p>
     * This is a pretty painful piece of code. It is trying to replicate
     * what AwsClient.setEndpoint() does, because you can't
     * call that setter on an AwsClient constructed via
     * the builder, and you can't pass a metrics collector
     * down except through the builder.
     * <p>
     * Note also that AWS signing is a mystery which nobody fully
     * understands, especially given all problems surface in a
     * "400 bad request" response, which, like all security systems,
     * provides minimal diagnostics out of fear of leaking
     * secrets.
     *
     * @param endpoint possibly null endpoint.
     * @param awsConf config to build the URI from.
     * @param awsRegion AWS S3 Region if the corresponding config is set.
     * @return a configuration for the S3 client builder.
     */
    @VisibleForTesting
    public static AwsClientBuilder.EndpointConfiguration
        createEndpointConfiguration(
        final String endpoint, final ClientConfiguration awsConf,
        String awsRegion) {
      LOG.debug("Creating endpoint configuration for \"{}\"", endpoint);
      if (endpoint == null || endpoint.isEmpty()) {
        // the default endpoint...we should be using null at this point.
        LOG.debug("Using default endpoint -no need to generate a configuration");
        return null;
      }
    
      final URI epr = RuntimeHttpUtils.toUri(endpoint, awsConf);
      LOG.debug("Endpoint URI = {}", epr);
      String region = awsRegion;
      if (StringUtils.isBlank(region)) {
        if (!ServiceUtils.isS3USStandardEndpoint(endpoint)) {
          LOG.debug("Endpoint {} is not the default; parsing", epr);
          region = AwsHostNameUtils.parseRegion(
              epr.getHost(),
              S3_SERVICE_NAME);
        } else {
          // US-east, set region == null.
          LOG.debug("Endpoint {} is the standard one; declare region as null",
              epr);
          region = null;
        }
      }
      LOG.debug("Region for endpoint {}, URI {} is determined as {}",
          endpoint, epr, region);
      return new AwsClientBuilder.EndpointConfiguration(endpoint, region);
    }
}
