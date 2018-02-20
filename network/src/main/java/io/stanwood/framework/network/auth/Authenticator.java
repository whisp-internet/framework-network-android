package io.stanwood.framework.network.auth;

import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.IOException;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

/**
 * This class will be called by okhttp upon receiving a 401 from the server which means we should
 * usually retry the request with a fresh token.
 * <p>
 * It is NOT called during initially making a request. For that refer to
 * {@link AuthInterceptor}.
 */
public class Authenticator implements okhttp3.Authenticator {

    @NonNull
    private final AuthenticationProvider authenticationProvider;
    @NonNull
    private final TokenReaderWriter tokenReaderWriter;
    @Nullable
    private final OnAuthenticationFailedListener onAuthenticationFailedListener;

    public Authenticator(
            @NonNull AuthenticationProvider authenticationProvider,
            @NonNull TokenReaderWriter tokenReaderWriter,
            @Nullable OnAuthenticationFailedListener onAuthenticationFailedListener
    ) {
        this.authenticationProvider = authenticationProvider;
        this.tokenReaderWriter = tokenReaderWriter;
        this.onAuthenticationFailedListener = onAuthenticationFailedListener;
    }

    @Override
    public Request authenticate(@NonNull Route route, @NonNull Response response) throws IOException {
        Request request = response.request();

        String oldToken = tokenReaderWriter.read(request);
        if (oldToken != null) {
            if (request.header(AuthHeaderKeys.RETRY_WITH_REFRESH_HEADER_KEY) != null) {
                synchronized (authenticationProvider.getLock()) {
                    String token;
                    try {
                        token = authenticationProvider.getToken(false);
                    } catch (Exception e) {
                        throw new IOException("Error while trying to retrieve auth token: " + e.getMessage(), e);
                    }

                    if (oldToken.equals(token)) {
                        /*
                        if the token we receive from the AuthenticationProvider hasn't changed in
                        the meantime (e.g. due to another request having triggered a 401 and
                        re-authenticating before us getting here), try to get a new one
                        */
                        try {
                            token = authenticationProvider.getToken(true);
                        } catch (Exception e) {
                            throw new IOException("Error while trying to retrieve auth token: " + e.getMessage(), e);
                        }
                    }

                    return tokenReaderWriter.write(
                            tokenReaderWriter.removeToken(
                                    request.newBuilder()
                                            .removeHeader(AuthHeaderKeys.RETRY_WITH_REFRESH_HEADER_KEY)
                                            .build()),
                            token);
                }
            } else {
                return onAuthenticationFailed(response);
            }
        }

        return request;
    }

    /**
     * Called upon ultimately failed authentication.
     * <br><br>
     * The default implementation just returns {@code null} and thus cancels the request. You can
     * return another Request here to attempt another try.
     *
     * @return Response containing the failed Request
     */
    @SuppressWarnings("WeakerAccess")
    @CallSuper
    @Nullable
    protected Request onAuthenticationFailed(@NonNull Response response) {
        // Give up, we've already failed to authenticate even after refreshing the token.
        if (onAuthenticationFailedListener != null) {
            onAuthenticationFailedListener.onAuthenticationFailed(response);
        }
        return null;
    }

    public interface OnAuthenticationFailedListener {
        void onAuthenticationFailed(@NonNull Response response);
    }
}