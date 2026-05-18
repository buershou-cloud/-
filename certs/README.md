# Alipay Certificate Files

For certificate-mode channels, create one folder per channel id:

```text
certs/<channel-id>/appCertPublicKey.crt
certs/<channel-id>/alipayCertPublicKey_RSA2.crt
certs/<channel-id>/alipayRootCert.crt
```

The real certificate files are ignored by git. Do not commit production credentials.
