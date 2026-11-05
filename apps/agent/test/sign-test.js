// Checks the web page's request signing (the r2sign block in index.html) against the agent's own.
// Usage: node sign-test.js <index.html> <expected signature from SecurityTest's vector>
const fs = require("fs");
const html = fs.readFileSync(process.argv[2], "utf8");
const m = html.match(/\/\/ BEGIN r2sign[\s\S]*?\/\/ END r2sign/);
if (!m) { console.log("FAIL: no r2sign block in the page"); process.exit(1); }
const R2Sign = new Function(m[0] + "\nreturn R2Sign;")();
let fail = 0;
const eq = (want, got, what) => { if (want !== got) { fail++; console.log("FAIL: " + what + ": want " + want + " got " + got); } };
const hex = b => R2Sign.hex(R2Sign.sha256(R2Sign.utf8(b)));
eq("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(""), "sha256 empty");
eq("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex("abc"), "sha256 abc");
eq("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
   hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"), "sha256 two blocks");
eq("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", hex("a".repeat(1000000)), "sha256 1M");
// RFC 4231 test case 2
eq("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
   R2Sign.hmac("Jefe", R2Sign.utf8("what do ya want for nothing?")), "hmac rfc4231");
// A key longer than a block is hashed first; compare with node's own HMAC.
const long = "k".repeat(100);
eq(require("crypto").createHmac("sha256", long).update("msg").digest("hex"), R2Sign.hmac(long, R2Sign.utf8("msg")), "hmac long key");
const canon = R2Sign.canonical("POST", "/action?r2sig=ignored&name=dance%20party&b=2", "1700000000", "abcdefghijklmnop0123",
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
eq(process.argv[3], R2Sign.hmac("test-key", R2Sign.utf8(canon)), "same signature as the agent");
const h = R2Sign.headers("c1", "k", "GET", "/status", undefined, 1700000000);
eq(true, /^[0-9a-f]{24}$/.test(h["X-R2-Nonce"]), "nonce format");
console.log(fail ? fail + " web page signing failures" : "web page signing: ok");
process.exit(fail ? 1 : 0);
