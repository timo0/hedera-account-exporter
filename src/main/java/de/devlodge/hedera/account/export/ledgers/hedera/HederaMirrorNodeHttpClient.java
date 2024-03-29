package de.devlodge.hedera.account.export.ledgers.hedera;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.devlodge.hedera.account.export.utils.HttpUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class HederaMirrorNodeHttpClient {

    public static final String MAINNET_MIRRORNODE_URL_BASE = "https://mainnet-public.mirrornode.hedera.com";

    public static final String GET_TRANSACTION_URL = MAINNET_MIRRORNODE_URL_BASE + "/api/v1/transactions?account.id=%s&order=asc&transactiontype=CRYPTOTRANSFER&result=success";
    private final HttpClient client;

    public HederaMirrorNodeHttpClient() {
        client = HttpClient.newHttpClient();
    }

    private List<HederaTransaction> request(final URI uri) throws IOException, InterruptedException {
        final JsonObject jsonObject = HttpUtils.getJsonElementForGetRequest(client, uri).getAsJsonObject();
        final JsonArray transactions = jsonObject
                .get("transactions")
                .getAsJsonArray();
        final List<HederaTransaction> hederaTransactions = new ArrayList<>(getHederaTransactions(transactions));

        if(jsonObject.has("links")  && !jsonObject.get("links").isJsonNull()) {
            final var links = jsonObject.get("links").getAsJsonObject();
            if(links.has("next") && !links.get("next").isJsonNull()) {
                final String next = links.get("next").getAsString();
                if(next != null && !next.isEmpty()) {
                    hederaTransactions.addAll(request(URI.create(MAINNET_MIRRORNODE_URL_BASE + next)));
                }
            }
        }

        return hederaTransactions;
    }

    public List<HederaTransaction> request(final String accountId) throws IOException, InterruptedException {
        return request(URI.create(String.format(GET_TRANSACTION_URL, accountId)));
    }

    private static List<HederaTransaction> getHederaTransactions(JsonArray transactions) {
        return transactions.asList()
                .stream()
                .map(transaction -> {
                    final List<HederaTransfer> hederaTransfers = new ArrayList<>(Optional.ofNullable(
                                    transaction.getAsJsonObject().get("transfers"))
                            .map(JsonElement::getAsJsonArray)
                            .orElseGet(JsonArray::new)
                            .asList()
                            .stream()
                            .map(transfer -> new HederaTransfer(
                                    transfer.getAsJsonObject().get("account").getAsString(),
                                    transfer.getAsJsonObject().get("amount").getAsLong()
                            ))
                            .toList());
                    final List<HederaTransfer> stakingRewardHederaTransfers = Optional.ofNullable(
                                    transaction.getAsJsonObject().get("staking_reward_transfers"))
                            .map(JsonElement::getAsJsonArray)
                            .orElseGet(JsonArray::new)
                            .asList()
                            .stream()
                            .map(transfer -> new HederaTransfer(
                                    transfer.getAsJsonObject().get("account").getAsString(),
                                    transfer.getAsJsonObject().get("amount").getAsLong()
                            ))
                            .toList();

                    hederaTransfers.replaceAll(hederaTransfer -> {
                        long amount = hederaTransfer.amount();
                        final long stackingAmount = stakingRewardHederaTransfers.stream()
                                .filter(stackingTransfer -> Objects.equals(hederaTransfer.account(),
                                        stackingTransfer.account()))
                                .map(stackingTransfer -> stackingTransfer.amount())
                                .reduce(0L, Long::sum);
                        return new HederaTransfer(hederaTransfer.account(), amount - stackingAmount);
                    });

                    return new HederaTransaction(
                            transaction.getAsJsonObject().get("transaction_id").getAsString(),
                            transaction.getAsJsonObject().get("consensus_timestamp").getAsString(),
                            hederaTransfers,
                            stakingRewardHederaTransfers
                    );
                })
                .toList();
    }
}
