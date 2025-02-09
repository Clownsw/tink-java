// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
///////////////////////////////////////////////////////////////////////////////

package com.google.crypto.tink.hybrid.internal;

import com.google.crypto.tink.HybridDecrypt;
import com.google.crypto.tink.Parameters;
import com.google.crypto.tink.Registry;
import com.google.crypto.tink.config.internal.TinkFipsUtil;
import com.google.crypto.tink.hybrid.HpkeParameters;
import com.google.crypto.tink.hybrid.HpkeProtoSerialization;
import com.google.crypto.tink.internal.BigIntegerEncoding;
import com.google.crypto.tink.internal.KeyTypeManager;
import com.google.crypto.tink.internal.MutableParametersRegistry;
import com.google.crypto.tink.internal.PrimitiveFactory;
import com.google.crypto.tink.internal.PrivateKeyTypeManager;
import com.google.crypto.tink.proto.HpkeKem;
import com.google.crypto.tink.proto.HpkeKeyFormat;
import com.google.crypto.tink.proto.HpkePrivateKey;
import com.google.crypto.tink.proto.HpkePublicKey;
import com.google.crypto.tink.proto.KeyData.KeyMaterialType;
import com.google.crypto.tink.subtle.EllipticCurves;
import com.google.crypto.tink.subtle.Validators;
import com.google.crypto.tink.subtle.X25519;
import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Key manager that generates new {@link HpkePrivateKey} keys and produces new instances of {@link
 * HpkeDecrypt} primitives.
 */
public final class HpkePrivateKeyManager
    extends PrivateKeyTypeManager<HpkePrivateKey, HpkePublicKey> {
  public HpkePrivateKeyManager() {
    super(
        HpkePrivateKey.class,
        HpkePublicKey.class,
        new PrimitiveFactory<HybridDecrypt, HpkePrivateKey>(HybridDecrypt.class) {
          @Override
          public HybridDecrypt getPrimitive(HpkePrivateKey recipientPrivateKey)
              throws GeneralSecurityException {
            return HpkeDecrypt.createHpkeDecrypt(recipientPrivateKey);
          }
        });
  }

  /**
   * Registers an {@link HpkePrivateKeyManager} and an {@link HpkePublicKeyManager} with the
   * registry, so that HpkePrivateKey and HpkePublicKey key types can be used with Tink.
   */
  public static void registerPair(boolean newKeyAllowed) throws GeneralSecurityException {
    Registry.registerAsymmetricKeyManagers(
        new HpkePrivateKeyManager(), new HpkePublicKeyManager(), newKeyAllowed);
    HpkeProtoSerialization.register();
    MutableParametersRegistry.globalInstance().putAll(namedParameters());
  }

  @Override
  public TinkFipsUtil.AlgorithmFipsCompatibility fipsStatus() {
    return TinkFipsUtil.AlgorithmFipsCompatibility.ALGORITHM_NOT_FIPS;
  }

  @Override
  public String getKeyType() {
    return "type.googleapis.com/google.crypto.tink.HpkePrivateKey";
  }

  @Override
  public int getVersion() {
    return 0;
  }

  @Override
  public HpkePublicKey getPublicKey(HpkePrivateKey key) {
    return key.getPublicKey();
  }

  @Override
  public KeyMaterialType keyMaterialType() {
    return KeyMaterialType.ASYMMETRIC_PRIVATE;
  }

  @Override
  public HpkePrivateKey parseKey(ByteString byteString) throws InvalidProtocolBufferException {
    return HpkePrivateKey.parseFrom(byteString, ExtensionRegistryLite.getEmptyRegistry());
  }

  @Override
  public void validateKey(HpkePrivateKey key) throws GeneralSecurityException {
    if (key.getPrivateKey().isEmpty()) {
      throw new GeneralSecurityException("Private key is empty.");
    }
    if (!key.hasPublicKey()) {
      throw new GeneralSecurityException("Missing public key.");
    }
    Validators.validateVersion(key.getVersion(), getVersion());
    HpkeUtil.validateParams(key.getPublicKey().getParams());
  }

  @Override
  public KeyTypeManager.KeyFactory<HpkeKeyFormat, HpkePrivateKey> keyFactory() {
    return new KeyTypeManager.KeyFactory<HpkeKeyFormat, HpkePrivateKey>(HpkeKeyFormat.class) {
      @Override
      public void validateKeyFormat(HpkeKeyFormat keyFormat) throws GeneralSecurityException {
        HpkeUtil.validateParams(keyFormat.getParams());
      }

      @Override
      public HpkeKeyFormat parseKeyFormat(ByteString byteString)
          throws InvalidProtocolBufferException {
        return HpkeKeyFormat.parseFrom(byteString, ExtensionRegistryLite.getEmptyRegistry());
      }

      @Override
      public HpkePrivateKey createKey(HpkeKeyFormat keyFormat) throws GeneralSecurityException {
        byte[] privateKeyBytes;
        byte[] publicKeyBytes;

        HpkeKem kem = keyFormat.getParams().getKem();
        switch (kem) {
          case DHKEM_X25519_HKDF_SHA256:
            privateKeyBytes = X25519.generatePrivateKey();
            publicKeyBytes = X25519.publicFromPrivate(privateKeyBytes);
            break;
          case DHKEM_P256_HKDF_SHA256:
          case DHKEM_P384_HKDF_SHA384:
          case DHKEM_P521_HKDF_SHA512:
            EllipticCurves.CurveType curveType =
                HpkeUtil.nistHpkeKemToCurve(keyFormat.getParams().getKem());
            KeyPair keyPair = EllipticCurves.generateKeyPair(curveType);
            publicKeyBytes =
                EllipticCurves.pointEncode(
                    curveType,
                    EllipticCurves.PointFormatType.UNCOMPRESSED,
                    ((ECPublicKey) keyPair.getPublic()).getW());
            privateKeyBytes =
                BigIntegerEncoding.toBigEndianBytesOfFixedLength(
                    ((ECPrivateKey) keyPair.getPrivate()).getS(),
                    HpkeUtil.getEncodedPrivateKeyLength(kem));
            break;
          default:
            throw new GeneralSecurityException("Invalid KEM");
        }

        HpkePublicKey publicKey =
            HpkePublicKey.newBuilder()
                .setVersion(getVersion())
                .setParams(keyFormat.getParams())
                .setPublicKey(ByteString.copyFrom(publicKeyBytes))
                .build();

        return HpkePrivateKey.newBuilder()
            .setVersion(getVersion())
            .setPublicKey(publicKey)
            .setPrivateKey(ByteString.copyFrom(privateKeyBytes))
            .build();
      }
    };
  }

  private static Map<String, Parameters> namedParameters() throws GeneralSecurityException {
        Map<String, Parameters> result = new HashMap<>();
        result.put(
            "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_128_GCM",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.TINK)
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_128_GCM)
                .build());
        result.put(
            "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_128_GCM_RAW",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_128_GCM)
                .build());
        result.put(
            "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.TINK)
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .build());
        result.put(
            "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .build());
        result.put(
            "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.TINK)
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
                .build());
        result.put(
            "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305_RAW",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
                .build());
        result.put(
            "DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.TINK)
                .setKemId(HpkeParameters.KemId.DHKEM_P256_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_128_GCM)
                .build());
        result.put(
            "DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM_RAW",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .setKemId(HpkeParameters.KemId.DHKEM_P256_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_128_GCM)
                .build());
        result.put(
            "DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_256_GCM",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.TINK)
                .setKemId(HpkeParameters.KemId.DHKEM_P256_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .build());
        result.put(
            "DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .setKemId(HpkeParameters.KemId.DHKEM_P256_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .build());
        result.put(
            "DHKEM_P384_HKDF_SHA384_HKDF_SHA384_AES_128_GCM",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.TINK)
                .setKemId(HpkeParameters.KemId.DHKEM_P384_HKDF_SHA384)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA384)
                .setAeadId(HpkeParameters.AeadId.AES_128_GCM)
                .build());
        result.put(
            "DHKEM_P384_HKDF_SHA384_HKDF_SHA384_AES_128_GCM_RAW",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .setKemId(HpkeParameters.KemId.DHKEM_P384_HKDF_SHA384)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA384)
                .setAeadId(HpkeParameters.AeadId.AES_128_GCM)
                .build());
        result.put(
            "DHKEM_P384_HKDF_SHA384_HKDF_SHA384_AES_256_GCM",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.TINK)
                .setKemId(HpkeParameters.KemId.DHKEM_P384_HKDF_SHA384)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA384)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .build());
        result.put(
            "DHKEM_P384_HKDF_SHA384_HKDF_SHA384_AES_256_GCM_RAW",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .setKemId(HpkeParameters.KemId.DHKEM_P384_HKDF_SHA384)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA384)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .build());
        result.put(
            "DHKEM_P521_HKDF_SHA512_HKDF_SHA512_AES_128_GCM",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.TINK)
                .setKemId(HpkeParameters.KemId.DHKEM_P521_HKDF_SHA512)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA512)
                .setAeadId(HpkeParameters.AeadId.AES_128_GCM)
                .build());
        result.put(
            "DHKEM_P521_HKDF_SHA512_HKDF_SHA512_AES_128_GCM_RAW",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .setKemId(HpkeParameters.KemId.DHKEM_P521_HKDF_SHA512)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA512)
                .setAeadId(HpkeParameters.AeadId.AES_128_GCM)
                .build());
        result.put(
            "DHKEM_P521_HKDF_SHA512_HKDF_SHA512_AES_256_GCM",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.TINK)
                .setKemId(HpkeParameters.KemId.DHKEM_P521_HKDF_SHA512)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA512)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .build());
        result.put(
            "DHKEM_P521_HKDF_SHA512_HKDF_SHA512_AES_256_GCM_RAW",
            HpkeParameters.builder()
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .setKemId(HpkeParameters.KemId.DHKEM_P521_HKDF_SHA512)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA512)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .build());
        return Collections.unmodifiableMap(result);
  }
}
