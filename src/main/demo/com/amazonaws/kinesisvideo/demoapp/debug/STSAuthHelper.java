package com.amazonaws.kinesisvideo.demoapp.debug;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.GetSessionTokenRequest;
import com.amazonaws.services.securitytoken.model.GetSessionTokenResult;
import com.amazonaws.services.securitytoken.model.Credentials;

public class STSAuthHelper implements AWSCredentialsProvider {
    private final AWSSecurityTokenService stsClient;
    private final int durationSeconds;
    private volatile AWSCredentials cachedCredentials;
    private volatile long credentialExpiration;

    private static final int DEFAULT_DURATION_SECONDS = 900; // 15 minutes minimum

    public STSAuthHelper() {
        this(DEFAULT_DURATION_SECONDS);
    }

    public STSAuthHelper(int durationSeconds) {
        // Use connectTest IAM user credentials
        BasicAWSCredentials iamCredentials = new BasicAWSCredentials(
                "", ""
        );

        this.stsClient = AWSSecurityTokenServiceClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(iamCredentials))
                .build();
        this.durationSeconds = durationSeconds;
        refreshCredentials(); // Get credentials once at startup
    }

    @Override
    public AWSCredentials getCredentials() {
        if (System.currentTimeMillis() >= credentialExpiration) {
            refreshCredentials();
        }
        return cachedCredentials;
    }

    @Override
    public void refresh() {
        refreshCredentials();
    }

    private synchronized void refreshCredentials() {
        System.out.println("[STS]: Actually Refreshing Credentials");
        GetSessionTokenResult result = stsClient.getSessionToken(
                new GetSessionTokenRequest().withDurationSeconds(durationSeconds)
        );

        Credentials creds = result.getCredentials();
        cachedCredentials = new BasicSessionCredentials(
                creds.getAccessKeyId(),
                creds.getSecretAccessKey(),
                creds.getSessionToken()
        );
        credentialExpiration = creds.getExpiration().getTime();
    }
}
