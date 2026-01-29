package com.dripswap.bff.util;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;

/**
 * Minimal EIP-712 signer matching apps/contracts/src/faucet/FaucetV2.sol.
 *
 * Claim struct:
 * Claim(address user,address token,uint256 amount,uint64 day,uint256 nonce,uint256 deadline,bool pass)
 */
public final class FaucetV2Eip712Signer {
    private static final byte[] EIP712_DOMAIN_TYPEHASH = Hash.sha3(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".getBytes(StandardCharsets.UTF_8)
    );
    private static final byte[] CLAIM_TYPEHASH = Hash.sha3(
            "Claim(address user,address token,uint256 amount,uint64 day,uint256 nonce,uint256 deadline,bool pass)".getBytes(StandardCharsets.UTF_8)
    );

    private FaucetV2Eip712Signer() {}

    public static byte[] signClaim(
            ECKeyPair signerKey,
            String eip712Name,
            String eip712Version,
            long chainId,
            String verifyingContract,
            String user,
            String token,
            BigInteger amount,
            long day,
            BigInteger nonce,
            BigInteger deadline,
            boolean pass
    ) {
        byte[] digest = digest(
                eip712Name,
                eip712Version,
                chainId,
                verifyingContract,
                user,
                token,
                amount,
                day,
                nonce,
                deadline,
                pass
        );
        Sign.SignatureData sig = Sign.signMessage(digest, signerKey, false);
        byte v = sig.getV() == null || sig.getV().length == 0 ? 0 : sig.getV()[0];
        if (v < 27) v = (byte) (v + 27);
        return join(sig.getR(), sig.getS(), new byte[] {v});
    }

    public static byte[] digest(
            String eip712Name,
            String eip712Version,
            long chainId,
            String verifyingContract,
            String user,
            String token,
            BigInteger amount,
            long day,
            BigInteger nonce,
            BigInteger deadline,
            boolean pass
    ) {
        byte[] domainSeparator = domainSeparator(eip712Name, eip712Version, chainId, verifyingContract);
        byte[] structHash = claimStructHash(user, token, amount, day, nonce, deadline, pass);
        byte[] prefix = new byte[] {0x19, 0x01};
        return Hash.sha3(join(prefix, domainSeparator, structHash));
    }

    private static byte[] domainSeparator(String name, String version, long chainId, String verifyingContract) {
        byte[] nameHash = Hash.sha3((name == null ? "" : name).getBytes(StandardCharsets.UTF_8));
        byte[] versionHash = Hash.sha3((version == null ? "" : version).getBytes(StandardCharsets.UTF_8));
        byte[] encoded = encodeAbi(List.of(
                new Bytes32(EIP712_DOMAIN_TYPEHASH),
                new Bytes32(nameHash),
                new Bytes32(versionHash),
                new Uint256(BigInteger.valueOf(chainId)),
                new Address(verifyingContract)
        ));
        return Hash.sha3(encoded);
    }

    private static byte[] claimStructHash(
            String user,
            String token,
            BigInteger amount,
            long day,
            BigInteger nonce,
            BigInteger deadline,
            boolean pass
    ) {
        byte[] encoded = encodeAbi(List.of(
                new Bytes32(CLAIM_TYPEHASH),
                new Address(user),
                new Address(token),
                new Uint256(amount),
                new Uint64(BigInteger.valueOf(day)),
                new Uint256(nonce),
                new Uint256(deadline),
                new Bool(pass)
        ));
        return Hash.sha3(encoded);
    }

    private static byte[] encodeAbi(List<Type> types) {
        String hex = FunctionEncoder.encodeConstructor(types);
        return org.web3j.utils.Numeric.hexStringToByteArray(hex);
    }

    private static byte[] join(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
