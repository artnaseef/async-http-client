/*
 * Copyright (c) 2013 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package org.asynchttpclient.providers.grizzly.statushandler;

import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.ntlm.NTLMEngine;
import org.asynchttpclient.providers.grizzly.ConnectionManager;
import org.asynchttpclient.providers.grizzly.HttpTransactionContext;
import org.asynchttpclient.util.AuthenticatorUtils;
import org.asynchttpclient.util.Base64;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;

public final class ProxyAuthorizationHandler implements StatusHandler {

    public static final ProxyAuthorizationHandler INSTANCE =
            new ProxyAuthorizationHandler();

    private final static NTLMEngine ntlmEngine = new NTLMEngine();


    // ---------------------------------------------- Methods from StatusHandler


    public boolean handlesStatus(int statusCode) {
        return (HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407.statusMatches(statusCode));
    }

    @SuppressWarnings({"unchecked"})
    public boolean handleStatus(final HttpResponsePacket responsePacket,
                             final HttpTransactionContext httpTransactionContext,
                             final FilterChainContext ctx) {

        final String proxy_auth = responsePacket.getHeader(Header.ProxyAuthenticate);
        if (proxy_auth == null) {
            throw new IllegalStateException("407 response received, but no Proxy Authenticate header was present");
        }

        final Request req = httpTransactionContext.getRequest();
        ProxyServer proxyServer = httpTransactionContext.getProvider().getClientConfig().getProxyServer();
        String principal = proxyServer.getPrincipal();
        String password = proxyServer.getPassword();
        Realm realm = new Realm.RealmBuilder().setPrincipal(principal)
                        .setPassword(password)
                        .setUri("/")
                        .setMethodName("CONNECT")
                        .setUsePreemptiveAuth(true)
                        .parseProxyAuthenticateHeader(proxy_auth)
                        .build();
        if (proxy_auth.toLowerCase().startsWith("basic")) {
            req.getHeaders().remove(Header.ProxyAuthenticate.toString());
            req.getHeaders().remove(Header.ProxyAuthorization.toString());
            try {
                req.getHeaders().add(Header.ProxyAuthorization.toString(),
                                     AuthenticatorUtils.computeBasicAuthentication(
                                             realm));
            } catch (UnsupportedEncodingException ignored) {
            }
        } else if (proxy_auth.toLowerCase().startsWith("digest")) {
            req.getHeaders().remove(Header.ProxyAuthenticate.toString());
            req.getHeaders().remove(Header.ProxyAuthorization.toString());
            try {
                req.getHeaders().add(Header.ProxyAuthorization.toString(),
                                     AuthenticatorUtils.computeDigestAuthentication(realm));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Digest authentication not supported", e);
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException("Unsupported encoding.", e);
            }
        }else if (proxy_auth.toLowerCase().startsWith("ntlm")) {

            req.getHeaders().remove(Header.ProxyAuthenticate.toString());
            req.getHeaders().remove(Header.ProxyAuthorization.toString());

            String msg;
            try {

                if(isNTLMFirstHandShake(proxy_auth))
                {
                    msg = ntlmEngine.generateType1Msg(proxyServer.getNtlmDomain(), "");
                }else {
                    String serverChallenge = proxy_auth.trim().substring("NTLM ".length());
                    msg = ntlmEngine.generateType3Msg(principal, password, proxyServer.getNtlmDomain(), proxyServer.getHost(), serverChallenge);
                }

                req.getHeaders().add(Header.ProxyAuthorization.toString(), "NTLM " + msg);
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        }   else if (proxy_auth.toLowerCase().startsWith("negotiate")){
            //this is for kerberos
            req.getHeaders().remove(Header.ProxyAuthenticate.toString());
            req.getHeaders().remove(Header.ProxyAuthorization.toString());

        }else {
            throw new IllegalStateException("Unsupported authorization method: " + proxy_auth);
        }

        final ConnectionManager m = httpTransactionContext.getProvider().getConnectionManager();
        InvocationStatus tempInvocationStatus = InvocationStatus.STOP;

        try {

            if(isNTLMFirstHandShake(proxy_auth))
            {
                tempInvocationStatus = InvocationStatus.CONTINUE;

            }

            if(proxy_auth.toLowerCase().startsWith("negotiate"))
            {
                final Connection c = m.obtainConnection(req, httpTransactionContext.getFuture());
                final HttpTransactionContext newContext = httpTransactionContext.copy();
                httpTransactionContext.setFuture(null);
                HttpTransactionContext.set(c, newContext);

                newContext.setInvocationStatus(tempInvocationStatus);

                String challengeHeader;
                String server = proxyServer.getHost();

                challengeHeader = GSSSPNEGOWrapper.generateToken(server);

                req.getHeaders().add(Header.ProxyAuthorization.toString(), "Negotiate " + challengeHeader);


                return exceuteRequest(httpTransactionContext, req, c,
                        newContext);
            }else if(isNTLMSecondHandShake(proxy_auth))
            {
                final Connection c = ctx.getConnection();
                final HttpTransactionContext newContext = httpTransactionContext.copy();

                httpTransactionContext.setFuture(null);
                HttpTransactionContext.set(c, newContext);

                newContext.setInvocationStatus(tempInvocationStatus);
                httpTransactionContext.setEstablishingTunnel(true);

                return exceuteRequest(httpTransactionContext, req, c,
                        newContext);

            }
            else{
                final Connection c = m.obtainConnection(req, httpTransactionContext.getFuture());
                final HttpTransactionContext newContext = httpTransactionContext.copy();
                httpTransactionContext.setFuture(null);
                HttpTransactionContext.set(c, newContext);

                newContext.setInvocationStatus(tempInvocationStatus);

                //NTLM needs the same connection to be used for exchange of tokens
                return exceuteRequest(httpTransactionContext, req, c,
                        newContext);
            }
        } catch (Exception e) {
            httpTransactionContext.abort(e);
        }
        httpTransactionContext.setInvocationStatus(tempInvocationStatus);
        return false;
    }

    private boolean exceuteRequest(
            final HttpTransactionContext httpTransactionContext,
            final Request req, final Connection c,
            final HttpTransactionContext newContext) {
        try {
            httpTransactionContext.getProvider().execute(c,
                                                         req,
                                                         httpTransactionContext.getHandler(),
                                                         httpTransactionContext.getFuture());
            return false;
        } catch (IOException ioe) {
            newContext.abort(ioe);
            return false;
        }
    }

    public static boolean isNTLMSecondHandShake(final String proxy_auth) {
        return (proxy_auth != null && proxy_auth.toLowerCase().startsWith("ntlm") && !proxy_auth.equalsIgnoreCase("ntlm"));
    }
    public static boolean isNTLMFirstHandShake(final String proxy_auth) {
        return (proxy_auth.equalsIgnoreCase("ntlm"));
    }


    private static final class GSSSPNEGOWrapper {
        private final static org.slf4j.Logger LOGGER = LoggerFactory.getLogger(
                GSSSPNEGOWrapper.class);
        private static final String KERBEROS_OID = "1.2.840.113554.1.2.2";

        static GSSManager getManager() {
            return GSSManager.getInstance();
        }

        static byte[] generateGSSToken(
                final byte[] input, final Oid oid, final String authServer) throws GSSException {
            byte[] token = input;
            if (token == null) {
                token = new byte[0];
            }
            GSSManager manager = getManager();
            GSSName serverName = manager.createName("HTTP@" + authServer, GSSName.NT_HOSTBASED_SERVICE);
            GSSContext gssContext = manager.createContext(
                    serverName.canonicalize(oid), oid, null, GSSContext.DEFAULT_LIFETIME);
            gssContext.requestMutualAuth(true);
            gssContext.requestCredDeleg(true);
            return gssContext.initSecContext(token, 0, token.length);
        }

        public static String generateToken(String authServer) {
            String returnVal = "";
            Oid oid;
            try {
                oid = new Oid(KERBEROS_OID);
                byte[] token = GSSSPNEGOWrapper.generateGSSToken(null, oid,
                                                                 authServer);
                returnVal = Base64.encode(token);
            } catch (GSSException e) {
                LOGGER.warn(e.toString(), e);
            }

            return returnVal;
        }
    }
} // END AuthorizationHandler
