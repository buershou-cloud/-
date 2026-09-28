import { readFile } from 'node:fs/promises';
import { MerchantClient } from './merchant-client.mjs';

const readKey = async name => process.env[name] ? readFile(process.env[name], 'utf8') : undefined;
try {
  const [operation = 'check', requestFile] = process.argv.slice(2);
  if (operation !== 'check' && !requestFile) throw new Error(`Usage: node merchant-example.mjs ${operation} ./request.json`);
  const client = new MerchantClient({
    apiBase: process.env.MERCHANT_API_BASE,
    merchantId: process.env.MERCHANT_ID,
    signType: process.env.SIGN_TYPE || 'MD5',
    md5Key: process.env.MERCHANT_MD5_KEY,
    privateKey: await readKey('MERCHANT_PRIVATE_KEY_FILE'),
    platformPublicKey: await readKey('PLATFORM_PUBLIC_KEY_FILE')
  });
  const body = requestFile ? JSON.parse(await readFile(requestFile, 'utf8')) : {};
  const response = await client.request(operation, body);
  console.log(JSON.stringify(response, null, 2));
  if (response.data?.status === 'FAILED') process.exitCode = 2;
} catch (error) {
  console.error(JSON.stringify({ code: error.code || 'CLIENT_ERROR', message: error.message, httpStatus: error.httpStatus }, null, 2));
  process.exitCode = 1;
}
