package com.dripswap.bff.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.dripswap.bff.config.FaucetV2Properties;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Bytes1;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.BatchRequest;
import org.web3j.protocol.core.BatchResponse;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.http.HttpService;

@Service
public class FaucetV2OnchainService {
    private final FaucetV2ChainRegistry chainRegistry;
    private final Map<Long, Web3j> web3ByChain = new ConcurrentHashMap<>();

    // keccak256("SIGNER_ROLE") from FaucetV2.sol
    private static final byte[] ROLE_SIGNER = Hash.sha3("SIGNER_ROLE".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final List<TypeReference<org.web3j.abi.datatypes.Type>> OUT_BOOL =
            List.of((TypeReference) new TypeReference<Bool>() {});

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final List<TypeReference<org.web3j.abi.datatypes.Type>> OUT_UINT256 =
            List.of((TypeReference) new TypeReference<Uint256>() {});

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final List<TypeReference<?>> OUT_EIP712_DOMAIN =
            (List) List.of(
                    new TypeReference<Bytes1>() {},
                    new TypeReference<Utf8String>() {},
                    new TypeReference<Utf8String>() {},
                    new TypeReference<Uint256>() {},
                    new TypeReference<Address>() {},
                    new TypeReference<Bytes32>() {},
                    new TypeReference<org.web3j.abi.datatypes.DynamicArray<Uint256>>() {}
            );

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final List<TypeReference<org.web3j.abi.datatypes.Type>> OUT_UINT64 =
            List.of((TypeReference) new TypeReference<Uint64>() {});

    public FaucetV2OnchainService(FaucetV2ChainRegistry chainRegistry) {
        this.chainRegistry = chainRegistry;
    }

    public Web3j web3(long chainId) {
        return web3ByChain.computeIfAbsent(chainId, id -> {
            FaucetV2Properties.Chain chain = chainRegistry.get(id)
                    .orElseThrow(() -> new IllegalArgumentException("unsupported chainId: " + id));
            return Web3j.build(new HttpService(chain.getRpcUrl()));
        });
    }

    public long latestBlockTimestampSeconds(long chainId) {
        try {
            var block = web3(chainId).ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().getBlock();
            return block.getTimestamp().longValueExact();
        } catch (Exception e) {
            throw new IllegalStateException("failed to read latest block timestamp for chainId=" + chainId, e);
        }
    }

    public Eip712DomainInfo eip712Domain(long chainId, String faucetAddress) {
        Function fn = new Function(
                "eip712Domain",
                List.of(),
                OUT_EIP712_DOMAIN
        );
        List<Type> out = call(chainId, faucetAddress, fn);
        if (out.size() < 5) return null;
        // fields(bytes1) ignored for now
        String name = (String) out.get(1).getValue();
        String version = (String) out.get(2).getValue();
        BigInteger cid = (BigInteger) out.get(3).getValue();
        String verifying = ((Address) out.get(4)).getValue();
        return new Eip712DomainInfo(name, version, cid.longValue(), verifying);
    }

    public BigInteger getBalanceWei(long chainId, String address) {
        try {
            return web3(chainId).ethGetBalance(address, DefaultBlockParameterName.LATEST).send().getBalance();
        } catch (Exception e) {
            throw new IllegalStateException("failed to read balance for chainId=" + chainId + " addr=" + address, e);
        }
    }

    public boolean isTokenWhitelisted(long chainId, String faucetAddress, String tokenAddress) {
        Function fn = new Function(
                "tokenWhitelist",
                List.of(new Address(tokenAddress)),
                List.of(new TypeReference<Bool>() {})
        );
        List<Type> out = call(chainId, faucetAddress, fn);
        return !out.isEmpty() && Boolean.TRUE.equals(out.get(0).getValue());
    }

    public BigInteger tokenDailyCap(long chainId, String faucetAddress, String tokenAddress) {
        Function fn = new Function(
                "tokenDailyCap",
                List.of(new Address(tokenAddress)),
                List.of(new TypeReference<Uint256>() {})
        );
        List<Type> out = call(chainId, faucetAddress, fn);
        if (out.isEmpty()) return BigInteger.ZERO;
        return (BigInteger) out.get(0).getValue();
    }

    public boolean isPaused(long chainId, String faucetAddress) {
        Function fn = new Function(
                "paused",
                List.of(),
                List.of(new TypeReference<Bool>() {})
        );
        List<Type> out = call(chainId, faucetAddress, fn);
        return !out.isEmpty() && Boolean.TRUE.equals(out.get(0).getValue());
    }

    public long currentDay(long chainId, String faucetAddress) {
        Function fn = new Function(
                "currentDay",
                List.of(),
                List.of(new TypeReference<Uint64>() {})
        );
        List<Type> out = call(chainId, faucetAddress, fn);
        if (out.isEmpty()) return 0L;
        return ((BigInteger) out.get(0).getValue()).longValue();
    }

    public boolean hasSignerRole(long chainId, String faucetAddress, String signerAddress) {
        Function fn = new Function(
                "hasRole",
                List.of(new Bytes32(ROLE_SIGNER), new Address(signerAddress)),
                List.of(new TypeReference<Bool>() {})
        );
        List<Type> out = call(chainId, faucetAddress, fn);
        return !out.isEmpty() && Boolean.TRUE.equals(out.get(0).getValue());
    }

    public boolean wasNonceUsedByUserRecently(long chainId, String faucetAddress, String userAddress, BigInteger nonce, long lookbackBlocks) {
        try {
            if (nonce == null || nonce.signum() < 0) return false;
            BigInteger latest = web3(chainId).ethBlockNumber().send().getBlockNumber();
            BigInteger from = latest.subtract(BigInteger.valueOf(Math.max(0L, lookbackBlocks)));
            if (from.signum() < 0) from = BigInteger.ZERO;

            // Claimed(address indexed user, address indexed token, uint256 amount, uint64 day, uint256 nonce)
            String topic0 = Hash.sha3String("Claimed(address,address,uint256,uint64,uint256)");
            String topic1 = "0x" + org.web3j.utils.Numeric.toHexStringNoPrefixZeroPadded(
                    org.web3j.utils.Numeric.toBigInt(userAddress),
                    64
            );

            EthFilter filter = new EthFilter(
                    org.web3j.protocol.core.DefaultBlockParameter.valueOf(from),
                    org.web3j.protocol.core.DefaultBlockParameterName.LATEST,
                    faucetAddress
            );
            filter.addSingleTopic(topic0);
            filter.addSingleTopic(topic1);

            List<Log> logs = web3(chainId).ethGetLogs(filter).send().getLogs().stream()
                    .map(x -> (Log) x.get())
                    .toList();

            for (Log l : logs) {
                String data = l.getData();
                if (data == null || data.equals("0x")) continue;
                // data = abi.encode(amount(uint256), day(uint64), nonce(uint256))
                @SuppressWarnings({"rawtypes", "unchecked"})
                List<Type> decoded = FunctionReturnDecoder.decode(data, List.of(
                        (TypeReference) new TypeReference<Uint256>() {},
                        (TypeReference) new TypeReference<Uint64>() {},
                        (TypeReference) new TypeReference<Uint256>() {}
                ));
                if (decoded.size() != 3) continue;
                BigInteger n = (BigInteger) decoded.get(2).getValue();
                if (nonce.equals(n)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public BigInteger issuedAmountForTokenOnDayRecently(long chainId, String faucetAddress, String tokenAddress, long day, long lookbackBlocks) {
        try {
            BigInteger latest = web3(chainId).ethBlockNumber().send().getBlockNumber();
            BigInteger from = latest.subtract(BigInteger.valueOf(Math.max(0L, lookbackBlocks)));
            if (from.signum() < 0) from = BigInteger.ZERO;

            // Claimed(address indexed user, address indexed token, uint256 amount, uint64 day, uint256 nonce)
            String topic0 = Hash.sha3String("Claimed(address,address,uint256,uint64,uint256)");
            String topic2 = "0x" + org.web3j.utils.Numeric.toHexStringNoPrefixZeroPadded(
                    org.web3j.utils.Numeric.toBigInt(tokenAddress),
                    64
            );

            EthFilter filter = new EthFilter(
                    org.web3j.protocol.core.DefaultBlockParameter.valueOf(from),
                    org.web3j.protocol.core.DefaultBlockParameterName.LATEST,
                    faucetAddress
            );
            filter.addSingleTopic(topic0);
            filter.addOptionalTopics((String) null, topic2); // topic1=user(any), topic2=token(exact)

            List<Log> logs = web3(chainId).ethGetLogs(filter).send().getLogs().stream()
                    .map(x -> (Log) x.get())
                    .toList();

            BigInteger sum = BigInteger.ZERO;
            for (Log l : logs) {
                String data = l.getData();
                if (data == null || data.equals("0x")) continue;
                @SuppressWarnings({"rawtypes", "unchecked"})
                List<Type> decoded = FunctionReturnDecoder.decode(data, List.of(
                        (TypeReference) new TypeReference<Uint256>() {},
                        (TypeReference) new TypeReference<Uint64>() {},
                        (TypeReference) new TypeReference<Uint256>() {}
                ));
                if (decoded.size() != 3) continue;
                BigInteger amount = (BigInteger) decoded.get(0).getValue();
                BigInteger d = (BigInteger) decoded.get(1).getValue();
                if (d.longValue() == day) {
                    sum = sum.add(amount);
                }
            }
            return sum;
        } catch (Exception e) {
            return BigInteger.ZERO;
        }
    }

    public BigInteger vaultBalanceOf(long chainId, String faucetAddress, String tokenAddress) {
        Function fn = new Function(
                "balanceOfToken",
                List.of(new Address(tokenAddress)),
                List.of(new TypeReference<Uint256>() {})
        );
        List<Type> out = call(chainId, faucetAddress, fn);
        if (out.isEmpty()) return BigInteger.ZERO;
        return (BigInteger) out.get(0).getValue();
    }

    /**
     * Batch reads tokenWhitelist + balanceOfToken for multiple tokens to reduce RPC roundtrips.
     * Falls back to single-call mode if the RPC endpoint doesn't support batch.
     */
    public Map<String, TokenState> batchTokenState(long chainId, String faucetAddress, List<String> tokenAddresses) {
        Map<String, TokenState> out = new HashMap<>();
        if (tokenAddresses == null || tokenAddresses.isEmpty()) return out;

        try {
            Web3j w3 = web3(chainId);
            BatchRequest batch = w3.newBatch();
            List<Request<?, ? extends org.web3j.protocol.core.Response<?>>> reqs = new ArrayList<>();

            // 1) tokenWhitelist(token)
            for (String token : tokenAddresses) {
                Function fn = new Function(
                        "tokenWhitelist",
                        List.of(new Address(token)),
                        List.of(new TypeReference<Bool>() {})
                );
                String data = FunctionEncoder.encode(fn);
                Transaction tx = Transaction.createEthCallTransaction(null, faucetAddress, data);
                Request<?, EthCall> r = w3.ethCall(tx, DefaultBlockParameterName.LATEST);
                reqs.add(r);
                batch.add(r);
            }

            // 2) balanceOfToken(token)
            for (String token : tokenAddresses) {
                Function fn = new Function(
                        "balanceOfToken",
                        List.of(new Address(token)),
                        List.of(new TypeReference<Uint256>() {})
                );
                String data = FunctionEncoder.encode(fn);
                Transaction tx = Transaction.createEthCallTransaction(null, faucetAddress, data);
                Request<?, EthCall> r = w3.ethCall(tx, DefaultBlockParameterName.LATEST);
                reqs.add(r);
                batch.add(r);
            }

            BatchResponse resp = batch.send();
            List<? extends org.web3j.protocol.core.Response<?>> responses = resp.getResponses();
            int n = tokenAddresses.size();
            if (responses == null || responses.size() < 2 * n) {
                throw new IllegalStateException("batch response size mismatch");
            }

            for (int i = 0; i < n; i++) {
                String token = tokenAddresses.get(i);
                boolean whitelisted = false;
                BigInteger bal = BigInteger.ZERO;

                EthCall w = (EthCall) responses.get(i);
                if (w != null && !w.hasError()) {
                    List<Type> decoded = FunctionReturnDecoder.decode(w.getValue(), OUT_BOOL);
                    if (!decoded.isEmpty()) whitelisted = Boolean.TRUE.equals(decoded.get(0).getValue());
                }

                EthCall b = (EthCall) responses.get(n + i);
                if (b != null && !b.hasError()) {
                    List<Type> decoded = FunctionReturnDecoder.decode(b.getValue(), OUT_UINT256);
                    if (!decoded.isEmpty()) bal = (BigInteger) decoded.get(0).getValue();
                }

                out.put(token, new TokenState(whitelisted, bal));
            }
            return out;
        } catch (Exception ignored) {
            // Fallback: per-token calls.
            for (String token : tokenAddresses) {
                boolean w = isTokenWhitelisted(chainId, faucetAddress, token);
                BigInteger b = vaultBalanceOf(chainId, faucetAddress, token);
                out.put(token, new TokenState(w, b));
            }
            return out;
        }
    }

    private List<Type> call(long chainId, String to, Function fn) {
        try {
            String data = FunctionEncoder.encode(fn);
            // from can be null for eth_call on most clients.
            Transaction tx = Transaction.createEthCallTransaction(null, to, data);
            String value = web3(chainId).ethCall(tx, DefaultBlockParameterName.LATEST).send().getValue();
            return FunctionReturnDecoder.decode(value, fn.getOutputParameters());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public record TokenState(boolean whitelisted, BigInteger vaultBalanceRaw) {}

    public record Eip712DomainInfo(String name, String version, long chainId, String verifyingContract) {}
}
