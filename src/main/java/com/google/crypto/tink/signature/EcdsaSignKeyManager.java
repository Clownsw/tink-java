// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.google.crypto.tink.signature;

import static com.google.crypto.tink.internal.TinkBugException.exceptionIsBug;

import com.google.crypto.tink.KeyTemplate;
import com.google.crypto.tink.PublicKeySign;
import com.google.crypto.tink.Registry;
import com.google.crypto.tink.config.internal.TinkFipsUtil;
import com.google.crypto.tink.internal.KeyTypeManager;
import com.google.crypto.tink.internal.PrimitiveFactory;
import com.google.crypto.tink.internal.PrivateKeyTypeManager;
import com.google.crypto.tink.proto.EcdsaKeyFormat;
import com.google.crypto.tink.proto.EcdsaParams;
import com.google.crypto.tink.proto.EcdsaPrivateKey;
import com.google.crypto.tink.proto.EcdsaPublicKey;
import com.google.crypto.tink.proto.KeyData.KeyMaterialType;
import com.google.crypto.tink.signature.internal.SigUtil;
import com.google.crypto.tink.subtle.EcdsaSignJce;
import com.google.crypto.tink.subtle.EllipticCurves;
import com.google.crypto.tink.subtle.SelfKeyTestValidators;
import com.google.crypto.tink.subtle.Validators;
import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * This key manager generates new {@code EcdsaPrivateKey} keys and produces new instances of {@code
 * EcdsaSignJce}.
 */
public final class EcdsaSignKeyManager
    extends PrivateKeyTypeManager<EcdsaPrivateKey, EcdsaPublicKey> {
  EcdsaSignKeyManager() {
    super(
        EcdsaPrivateKey.class,
        EcdsaPublicKey.class,
        new PrimitiveFactory<PublicKeySign, EcdsaPrivateKey>(PublicKeySign.class) {
          @Override
          public PublicKeySign getPrimitive(EcdsaPrivateKey key) throws GeneralSecurityException {
            ECPrivateKey privateKey =
                EllipticCurves.getEcPrivateKey(
                    SigUtil.toCurveType(key.getPublicKey().getParams().getCurve()),
                    key.getKeyValue().toByteArray());

            ECPublicKey publicKey =
                EllipticCurves.getEcPublicKey(
                    SigUtil.toCurveType(key.getPublicKey().getParams().getCurve()),
                    key.getPublicKey().getX().toByteArray(),
                    key.getPublicKey().getY().toByteArray());

            SelfKeyTestValidators.validateEcdsa(
                privateKey,
                publicKey,
                SigUtil.toHashType(key.getPublicKey().getParams().getHashType()),
                SigUtil.toEcdsaEncoding(key.getPublicKey().getParams().getEncoding()));

            return new EcdsaSignJce(
                privateKey,
                SigUtil.toHashType(key.getPublicKey().getParams().getHashType()),
                SigUtil.toEcdsaEncoding(key.getPublicKey().getParams().getEncoding()));
          }
        });
  }

  @Override
  public String getKeyType() {
    return "type.googleapis.com/google.crypto.tink.EcdsaPrivateKey";
  }

  @Override
  public int getVersion() {
    return 0;
  }

  @Override
  public EcdsaPublicKey getPublicKey(EcdsaPrivateKey key) throws GeneralSecurityException {
    return key.getPublicKey();
  }

  @Override
  public KeyMaterialType keyMaterialType() {
    return KeyMaterialType.ASYMMETRIC_PRIVATE;
  }

  @Override
  public EcdsaPrivateKey parseKey(ByteString byteString) throws InvalidProtocolBufferException {
    return EcdsaPrivateKey.parseFrom(byteString, ExtensionRegistryLite.getEmptyRegistry());
  }

  @Override
  public void validateKey(EcdsaPrivateKey privKey) throws GeneralSecurityException {
    Validators.validateVersion(privKey.getVersion(), getVersion());
    SigUtil.validateEcdsaParams(privKey.getPublicKey().getParams());
  }

  @Override
  public KeyTypeManager.KeyFactory<EcdsaKeyFormat, EcdsaPrivateKey> keyFactory() {
    return new KeyTypeManager.KeyFactory<EcdsaKeyFormat, EcdsaPrivateKey>(EcdsaKeyFormat.class) {
      @Override
      public void validateKeyFormat(EcdsaKeyFormat format) throws GeneralSecurityException {
        SigUtil.validateEcdsaParams(format.getParams());
      }

      @Override
      public EcdsaKeyFormat parseKeyFormat(ByteString byteString)
          throws InvalidProtocolBufferException {
        return EcdsaKeyFormat.parseFrom(byteString, ExtensionRegistryLite.getEmptyRegistry());
      }

      @Override
      public EcdsaPrivateKey createKey(EcdsaKeyFormat format) throws GeneralSecurityException {
        EcdsaParams ecdsaParams = format.getParams();
        KeyPair keyPair =
            EllipticCurves.generateKeyPair(SigUtil.toCurveType(ecdsaParams.getCurve()));
        ECPublicKey pubKey = (ECPublicKey) keyPair.getPublic();
        ECPrivateKey privKey = (ECPrivateKey) keyPair.getPrivate();
        ECPoint w = pubKey.getW();

        // Creates EcdsaPublicKey.
        EcdsaPublicKey ecdsaPubKey =
            EcdsaPublicKey.newBuilder()
                .setVersion(getVersion())
                .setParams(ecdsaParams)
                .setX(ByteString.copyFrom(w.getAffineX().toByteArray()))
                .setY(ByteString.copyFrom(w.getAffineY().toByteArray()))
                .build();

        // Creates EcdsaPrivateKey.
        return EcdsaPrivateKey.newBuilder()
            .setVersion(getVersion())
            .setPublicKey(ecdsaPubKey)
            .setKeyValue(ByteString.copyFrom(privKey.getS().toByteArray()))
            .build();
      }

      @Override
      public Map<String, KeyTemplate> namedKeyTemplates(String typeUrl)
          throws GeneralSecurityException {
        Map<String, KeyTemplate> result = new HashMap<>();
        result.put("ECDSA_P256", KeyTemplate.createFrom(PredefinedSignatureParameters.ECDSA_P256));
        // This key template does not make sense because IEEE P1363 mandates a raw signature.
        // It is needed to maintain backward compatibility with SignatureKeyTemplates.
        // TODO(b/185475349): remove this in 2.0.0.
        result.put(
            "ECDSA_P256_IEEE_P1363",
            KeyTemplate.createFrom(PredefinedSignatureParameters.ECDSA_P256_IEEE_P1363));
        result.put(
            "ECDSA_P256_RAW",
            KeyTemplate.createFrom(
                EcdsaParameters.builder()
                    .setHashType(EcdsaParameters.HashType.SHA256)
                    .setCurveType(EcdsaParameters.CurveType.NIST_P256)
                    .setSignatureEncoding(EcdsaParameters.SignatureEncoding.IEEE_P1363)
                    .setVariant(EcdsaParameters.Variant.NO_PREFIX)
                    .build()));
        // This key template is identical to ECDSA_P256_RAW.
        // It is needed to maintain backward compatibility with SignatureKeyTemplates.
        // TODO(b/185475349): remove this in 2.0.0.
        result.put(
            "ECDSA_P256_IEEE_P1363_WITHOUT_PREFIX",
            KeyTemplate.createFrom(
                PredefinedSignatureParameters.ECDSA_P256_IEEE_P1363_WITHOUT_PREFIX));
        // TODO(b/140101381): This template is confusing and will be removed.
        result.put("ECDSA_P384", KeyTemplate.createFrom(PredefinedSignatureParameters.ECDSA_P384));
        // TODO(b/185475349): remove this in 2.0.0.
        result.put(
            "ECDSA_P384_IEEE_P1363",
            KeyTemplate.createFrom(PredefinedSignatureParameters.ECDSA_P384_IEEE_P1363));
        result.put(
            "ECDSA_P384_SHA512",
            KeyTemplate.createFrom(
                EcdsaParameters.builder()
                    .setHashType(EcdsaParameters.HashType.SHA512)
                    .setCurveType(EcdsaParameters.CurveType.NIST_P384)
                    .setSignatureEncoding(EcdsaParameters.SignatureEncoding.DER)
                    .setVariant(EcdsaParameters.Variant.TINK)
                    .build()));
        result.put(
            "ECDSA_P384_SHA384",
            KeyTemplate.createFrom(
                EcdsaParameters.builder()
                    .setHashType(EcdsaParameters.HashType.SHA384)
                    .setCurveType(EcdsaParameters.CurveType.NIST_P384)
                    .setSignatureEncoding(EcdsaParameters.SignatureEncoding.DER)
                    .setVariant(EcdsaParameters.Variant.TINK)
                    .build()));
        result.put("ECDSA_P521", KeyTemplate.createFrom(PredefinedSignatureParameters.ECDSA_P521));
        // TODO(b/185475349): remove this in 2.0.0.
        result.put(
            "ECDSA_P521_IEEE_P1363",
            KeyTemplate.createFrom(PredefinedSignatureParameters.ECDSA_P521_IEEE_P1363));
        return Collections.unmodifiableMap(result);
      }
    };
  }

  @Override
  public TinkFipsUtil.AlgorithmFipsCompatibility fipsStatus() {
    return TinkFipsUtil.AlgorithmFipsCompatibility.ALGORITHM_REQUIRES_BORINGCRYPTO;
  };

  /**
   * Registers the {@link EcdsaSignKeyManager} and the {@link EcdsaVerifyKeyManager} with the
   * registry, so that the the Ecdsa-Keys can be used with Tink.
   */
  public static void registerPair(boolean newKeyAllowed) throws GeneralSecurityException {
    Registry.registerAsymmetricKeyManagers(
        new EcdsaSignKeyManager(), new EcdsaVerifyKeyManager(), newKeyAllowed);
    EcdsaProtoSerialization.register();
  }

  /**
   * @return A {@link KeyTemplate} that generates new instances of ECDSA keys with the following
   *     parameters:
   *     <ul>
   *       <li>Hash function: SHA256
   *       <li>Curve: NIST P-256
   *       <li>Signature encoding: DER (this is the encoding that Java uses).
   *       <li>Prefix type: {@link KeyTemplate.OutputPrefixType#TINK}.
   *     </ul>
   */
  public static final KeyTemplate ecdsaP256Template() {
    return exceptionIsBug(
        () ->
            KeyTemplate.createFrom(
                EcdsaParameters.builder()
                    .setSignatureEncoding(EcdsaParameters.SignatureEncoding.DER)
                    .setCurveType(EcdsaParameters.CurveType.NIST_P256)
                    .setHashType(EcdsaParameters.HashType.SHA256)
                    .setVariant(EcdsaParameters.Variant.TINK)
                    .build()));
  }

  /**
   * @return A {@link KeyTemplate} that generates new instances of ECDSA keys with the following
   *     parameters:
   *     <ul>
   *       <li>Hash function: SHA256
   *       <li>Curve: NIST P-256
   *       <li>Signature encoding: DER (this is the encoding that Java uses).
   *       <li>Prefix type: RAW (no prefix).
   *     </ul>
   *     Keys generated from this template create raw signatures of exactly 64 bytes. It is
   *     compatible with JWS and most other libraries.
   */
  public static final KeyTemplate rawEcdsaP256Template() {
    return exceptionIsBug(
        () ->
            KeyTemplate.createFrom(
                EcdsaParameters.builder()
                    .setSignatureEncoding(EcdsaParameters.SignatureEncoding.IEEE_P1363)
                    .setCurveType(EcdsaParameters.CurveType.NIST_P256)
                    .setHashType(EcdsaParameters.HashType.SHA256)
                    .setVariant(EcdsaParameters.Variant.NO_PREFIX)
                    .build()));
  }

}
