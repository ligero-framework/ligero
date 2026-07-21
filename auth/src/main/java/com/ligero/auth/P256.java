package com.ligero.auth;

import java.security.AlgorithmParameters;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;

/** The NIST P-256 (secp256r1) domain parameters, resolved from the JDK. */
final class P256 {

    private P256() {
    }

    static ECParameterSpec params() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException("P-256 curve unavailable", e);
        }
    }
}
