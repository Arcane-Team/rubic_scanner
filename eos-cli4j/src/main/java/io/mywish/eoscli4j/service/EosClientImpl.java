package io.mywish.eoscli4j.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mywish.eoscli4j.BlockCallback;
import io.mywish.eoscli4j.EosClient;
import io.mywish.eoscli4j.model.request.BalanceRequest;
import io.mywish.eoscli4j.model.request.BlockRequest;
import io.mywish.eoscli4j.model.request.Request;
import io.mywish.eoscli4j.model.response.BalanceResponse;
import io.mywish.eoscli4j.model.response.BlockResponse;
import io.mywish.eoscli4j.model.response.ChainInfoResponse;
import io.mywish.eoscli4j.model.response.Error;
import io.mywish.eoscli4j.model.response.Response;
import io.mywish.eoscli4j.service.TcpClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.Charset;

@Slf4j
public class EosClientImpl implements EosClient {
    private final HttpClient client;
    private final URI rpc;
    private final ObjectMapper objectMapper;
    private Charset UTF8;
    private final String tcpHost;
    private final int tcpPort;

    public EosClientImpl(String tcpHost, int tcpPort, HttpClient client, URI rpc, ObjectMapper objectMapper) throws Exception {
        this.tcpHost = tcpHost;
        this.tcpPort = tcpPort;
        this.client = client;
        this.rpc = rpc;
        this.objectMapper = objectMapper;
        this.UTF8 = Charset.forName("UTF-8");
    }

    private <T extends Request> JsonNode doRequest(final String endpoint, final T request) throws Exception {
        HttpPost httpPost = new HttpPost(rpc + endpoint);
        String json = request == null ? "" : objectMapper.writeValueAsString(request);
        httpPost.setEntity(new StringEntity(json));
        HttpResponse httpResponse = this.client.execute(httpPost);
        HttpEntity entity = httpResponse.getEntity();
        String responseBody = EntityUtils.toString(entity, UTF8);
        JsonNode node = objectMapper.readTree(responseBody);
        if (node.get("error") != null) {
            Error error = objectMapper.treeToValue(node, Response.class).getError();
            throw new Exception(error.toString());
        }
        return node;
    }

    @Override
    public ChainInfoResponse getChainInfo() throws Exception {
        return objectMapper.treeToValue(
                doRequest("/v1/chain/get_info", null),
                ChainInfoResponse.class
        );
    }

    private BlockResponse parseBlock(JsonNode blockNode) throws Exception {
//        ObjectNode node = (ObjectNode)blockNode;
//        ArrayNode transactions = objectMapper.createArrayNode();
//        blockNode.get("transactions").forEach(txNode -> {
//            if (!txNode.get("trx").isTextual()) {
//                transactions.add(txNode);
//            }
//        });
//        node.set("transactions", transactions);
        return objectMapper.treeToValue(blockNode, BlockResponse.class);
    }

    @Override
    public BlockResponse getBlock(String hash) throws Exception {
        return parseBlock(doRequest("/v1/chain/get_block", new BlockRequest(hash)));
    }

    @Override
    public BlockResponse getBlock(Long number) throws Exception {
        return parseBlock(doRequest("/v1/chain/get_block", new BlockRequest(number)));
    }

    @Override
    public BalanceResponse getBalance(String code, String account) throws Exception {
        JsonNode node = doRequest("/v1/chain/get_currency_balance", new BalanceRequest(code, account));
        String[] unformatted = node.get(0).textValue().split(" ")[0].split("\\.");
        return new BalanceResponse(new BigInteger(unformatted[0] + unformatted[1]), unformatted[1].length());
    }

    @Override
    public void subscribe(Long lastBlock, BlockCallback callback) throws Exception {
        TcpClient tcpClient = new TcpClient(tcpHost, tcpPort);

        String lastBlockNo = lastBlock == null ? "" : String.valueOf(lastBlock);
        log.info("Begin subscription from {} block.", lastBlockNo);
        tcpClient.write("s" + lastBlockNo + "\n");

        while (true) {
            try {
                int length = tcpClient.readInt();
                String blockJSON = tcpClient.readString(length);
                BlockResponse block = parseBlock(objectMapper.readTree(blockJSON));
                if (!callback.callback(block)) {
                    log.info("Subscription terminated by consumer.");
                    break;
                }
                lastBlock = block.getBlockNum();
            }
            catch (java.io.EOFException e) {
                log.error("Socket failed. Terminate subscription.", e);
                break;
            }
            catch (Exception e) {
                log.warn("Error getting block {}. Get next.", lastBlock, e);
            }
        }
        tcpClient.close();
    }
}
