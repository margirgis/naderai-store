const nacl = require('tweetnacl');
const fs = require('fs');

const key = process.argv[2];
const value = fs.readFileSync(process.argv[3], 'utf8').trim();

const keyBytes = Buffer.from(key, 'base64');
const ephemeralKeyPair = nacl.box.keyPair();
const nonce = nacl.randomBytes(nacl.box.nonceLength);
const message = Buffer.from(value, 'utf8');
const encrypted = nacl.box(message, nonce, keyBytes, ephemeralKeyPair.secretKey);

if (!encrypted) {
  console.error('Encryption failed');
  process.exit(1);
}

const result = Buffer.concat([ephemeralKeyPair.publicKey, nonce, Buffer.from(encrypted)]);
console.log(result.toString('base64'));
