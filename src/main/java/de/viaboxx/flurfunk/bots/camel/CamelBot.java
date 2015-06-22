/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.viaboxx.flurfunk.bots.camel;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.io.*;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.irc.IrcMessage;
import org.apache.camel.component.mail.MailMessage;
import org.apache.camel.spring.Main;
import org.constretto.ConstrettoBuilder;
import org.constretto.ConstrettoConfiguration;
import org.constretto.model.Resource;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.apache.commons.lang3.StringEscapeUtils.escapeHtml4;

/**
 * A Camel Router
 */
public class CamelBot extends RouteBuilder {

    private static final String DIRECT_HIPCHAT_PROCESSOR = "direct:hipchatProcessor";

    /**
     * Launch the app.
     * <p/>
     * Camelbot will read a default camelbot.properties from the classpath. If you wish to override these settings,
     * either use staged properties (constretto.org style) - or create a camelbot-overrides.properties file.
     */
    public static void main(String... args) throws Exception {
        Main.main(args);
    }

    /**
     * Lets configure the Camel routing rules using Java code...
     */
    public void configure() {
        ConstrettoConfiguration config = configureConstretto();
        fromIrcRoute(config);
        fromImapRoute(config);
        toHipChat(config);
    }

    private ConstrettoConfiguration configureConstretto() {

        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("META-INF/maven/de.viaboxx.flurfunk/flurfunk-camelbot/pom.properties");
        if (inputStream != null) {
            System.out.println("==========================");
            System.out.println("Flurfunk C A M E L B O T !");
            System.out.println("==========================");
            System.out.println("http://flurfunk.github.com");
            System.out.println("==========================");
            Properties prop = new Properties();
            try {
                prop.load(inputStream);
                String version = prop.getProperty("version");
                System.out.println("Starting flurfunk-camelbot:" + version);
            } catch (IOException e) {
                System.out.println("WARNING: No meta information found in this camelbot distribution. Could be it's broken!");
            }
        }

        String propFile = System.getProperty("camelbotProps");
        if (propFile == null) {
            System.out.println("No camelbotProps system property specified. Will look in /etc/camelbot.properties for configuration.");
            propFile = "file:/etc/camelbot.properties";
        } else propFile = "file:" + propFile;

        ConstrettoConfiguration config = new ConstrettoBuilder()
                .createPropertiesStore()
                .addResource(Resource.create("classpath:camelbot.properties"))
                .addResource(Resource.create("classpath:camelbot-overrides.properties"))
                .addResource(Resource.create(propFile))
                .done()
                .getConfiguration();
        return config;
    }

    private void fromImapRoute(ConstrettoConfiguration config) {
        String username = config.evaluateToString("imapUserName");
        String password = config.evaluateToString("imapPassword");
        String imapFolder = config.evaluateToString("imapFolder"); //camelbot
        String pollingFreq = config.evaluateToString("imapPollingFrequency");

        from(String.format("imaps://imap.gmail.com?consumer.delay=%s&username=%s&password=%s&folderName=%s", pollingFreq, username, password, imapFolder)).
                process(new MailProcessor()).
                to(DIRECT_HIPCHAT_PROCESSOR);
    }


    private void fromIrcRoute(ConstrettoConfiguration config) {
        String ircServer = config.evaluateToString("ircServer");  //irc.irccloud.com
        String ircChannel = config.evaluateToString("ircChannel"); //#flurfunk
        String messagePrefix = config.evaluateToString("ircMessagePrefix"); //'camelbot: '

        from(String.format("irc:camelbot@%s?channels=%s", ircServer, ircChannel)).
                choice().
                when(body().startsWith(messagePrefix)).process(new IrcProcessor(messagePrefix)).
                to(DIRECT_HIPCHAT_PROCESSOR);
    }

    private void toHipChat(ConstrettoConfiguration config) {
        from("direct:hipchatProcessor")
                .process(new HipChatProcessor(config));
    }

    private static class HipChatProcessor implements Processor {
        private final ConstrettoConfiguration config;

        public HipChatProcessor(ConstrettoConfiguration config) {
            this.config = config;
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            String message = (String) exchange.getIn().getBody();
            String authToken = config.evaluateToString("hipchatAuthToken");
            String roomId = config.evaluateToString("hipchatRoomId");
            String from = config.evaluateToString("hipchatBotName");
            sendMessage(message, from, "green", roomId, true, authToken);
        }

        /**
         * See https://www.hipchat.com/docs/api/method/rooms/message for possible options
         *
         * @param message
         * @param from
         * @param color
         * @param roomId
         * @param notify
         * @param authToken
         */
        public void sendMessage(String message, String from, String color, String roomId, boolean notify, String authToken) throws IOException {
            String query = String.format("?format=%s&auth_token=%s", "json", authToken);

            StringBuilder params = new StringBuilder();

            Preconditions.checkNotNull(message, "Cannot send null message");
            Preconditions.checkNotNull(from, "Cannot send message without from-field");

            params.append("room_id=");
            params.append(roomId);
            params.append("&from=");
            params.append(URLEncoder.encode(from, "UTF-8"));
            params.append("&message=");
            params.append(URLEncoder.encode(message, "UTF-8"));

            if (notify) {
                params.append("&notify=1");
            }

            if (color != null) {
                params.append("&color=");
                params.append(color);
            }

            final String paramsToSend = params.toString();

            final HttpURLConnection connection;
            URL requestUrl = new URL("https://api.hipchat.com/v1/rooms/message" + query);
            connection = (HttpURLConnection) requestUrl.openConnection();
            connection.setDoOutput(true);

            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Content-Length", Integer.toString(paramsToSend.getBytes().length));
            connection.setRequestProperty("Content-Language", "en-US");

            sendRequest(paramsToSend, connection);

            String response = readResponse(connection);
            System.out.println("Sent parameters: " + paramsToSend + " - Received response from hipchat: " +response);
            connection.disconnect();
        }

        private String readResponse(final HttpURLConnection connection) throws IOException {
            InputSupplier<InputStreamReader> supplier = CharStreams.newReaderSupplier(new InputSupplier<InputStream>() {
                @Override
                public InputStream getInput() throws IOException {
                    return connection.getInputStream();
                }
            }, Charset.defaultCharset());
            return CharStreams.toString(supplier);
        }

        private void sendRequest(String paramsToSend, final HttpURLConnection connection) throws IOException {
            OutputSupplier<BufferedOutputStream> outputSupplier = new OutputSupplier<BufferedOutputStream>() {
                @Override
                public BufferedOutputStream getOutput() throws IOException {
                    return new BufferedOutputStream(connection.getOutputStream());
                }
            };
            ByteStreams.write(paramsToSend.getBytes(), outputSupplier);
        }

    }

    private static class MailProcessor implements Processor {

        @Override
        public void process(Exchange exchange) throws Exception {
            MailMessage message = (MailMessage) exchange.getIn();

            String from = message.getMessage().getFrom()[0].toString();
            String subject = message.getMessage().getSubject();

            List<String> channels = new ArrayList<String>();

            //TODO: Not very portable to have these set of channels.. Make it configurable at some point.
            if (subject.contains("[commits]")) channels.add("commits");
            if (subject.contains("[ci]")) channels.add("ci");
            if (subject.contains("Service Alert")) channels.add("nagios");

            String channelsCommaSeparated = Joiner.on(',').join(channels);
            exchange.getIn().setBody(messageString(from, subject, "", channelsCommaSeparated));
        }

    }

    private static class IrcProcessor implements Processor {
        private final String messagePrefix;

        public IrcProcessor(String messagePrefix) {

            this.messagePrefix = messagePrefix;
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            IrcMessage ircMsg = (IrcMessage) exchange.getIn();
            String message = ircMsg.getMessage();
            String user = ircMsg.getUser().toString();
            String ircChannel = ircMsg.getTarget();
            String ircServer = ircMsg.getUser().getServername();

            String subject = String.format("Chatted on %s", ircChannel);
            //ircMessage starts with 'camelbot: ' - cut away that part
            String body = message.substring(messagePrefix.length() + 1, message.length());

            exchange.getIn().setBody(messageString(user, subject, body, "irc"));
        }
    }

    /**
     * <pre>
     * POST http://flurfunk/message
     * Content-Type: application/xml
     * <message author="felix">
     * Hello, World!
     * </message>
     * </pre>
     */
    private static String messageString(String from, String subject, String body, String channels) {

        StringBuilder messageBuilder = new StringBuilder().
                append(subject).
                append("\n").
                append(body);
        //TODO: Append urls!

        StringBuilder xmlBuilder = new StringBuilder().
                append("<message channels='" + escapeHtml4(channels) + "' author='" + escapeHtml4(from) + "'>").
                append("<![CDATA[").
                append(messageBuilder.toString()).
                append("]]>").
                append("</message>");
        return xmlBuilder.toString();
    }


}
