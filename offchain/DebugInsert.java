// package offchain;
// ///usr/bin/env jbang "$0" "$@" ; exit $?
// //JAVA 21
// //DEPS com.bloxbean.cardano:cardano-client-lib:0.7.1
// //DEPS com.bloxbean.cardano:cardano-client-backend-blockfrost:0.7.1
// //DEPS com.bloxbean.cardano:aiken-java-binding:0.1.0
// //DEPS org.slf4j:slf4j-simple:2.0.9

// import com.bloxbean.cardano.aiken.AikenScriptUtil;
// import com.bloxbean.cardano.client.account.Account;
// import com.bloxbean.cardano.client.address.AddressProvider;
// import com.bloxbean.cardano.client.api.exception.ApiException;
// import com.bloxbean.cardano.client.api.model.Amount;
// import com.bloxbean.cardano.client.api.model.Result;
// import com.bloxbean.cardano.client.api.model.Utxo;
// import com.bloxbean.cardano.client.backend.api.BackendService;
// import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
// import com.bloxbean.cardano.client.common.model.Networks;
// import com.bloxbean.cardano.client.exception.CborDeserializationException;
// import com.bloxbean.cardano.client.function.helper.SignerProviders;
// import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintLoader;
// import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
// import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusContractBlueprint;
// import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
// import com.bloxbean.cardano.client.plutus.spec.*;
// import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
// import com.bloxbean.cardano.client.quicktx.ScriptTx;
// import com.bloxbean.cardano.client.transaction.spec.Asset;
// import com.bloxbean.cardano.client.util.HexUtil;

// import java.io.File;
// import java.math.BigInteger;
// import java.util.List;
// import java.util.stream.Collectors;

// /**
//  * Debug script to examine the Insert transaction BEFORE script cost evaluation.
//  * Uses a custom ScriptCostEvaluator that always returns dummy costs (skips Ogmios call).
//  */
// public class DebugInsert {

//     static final String BLOCKFROST_URL  = "http://localhost:8080/api/v1/";
//     static final String BLOCKFROST_ID   = "localnet";
//     static final String SENDER_MNEMONIC =
//             "test test test test test test test test test test test test " +
//             "test test test test test test test test test test test sauce";
//     static final BigInteger NODE_MIN_LOVELACE = BigInteger.valueOf(2_000_000);
//     static final String FSN_PREFIX_HEX = "46534e";

//     public static void main(String[] args) throws Exception {
//         BackendService bs = new BFBackendService(BLOCKFROST_URL, BLOCKFROST_ID);
//         QuickTxBuilder qb = new QuickTxBuilder(bs);

//         Account sender = Account.createFromMnemonic(Networks.testnet(), SENDER_MNEMONIC);
//         // Use a fixed user for reproducibility in debugging
//         Account user = new Account(Networks.testnet());

//         String senderAddr = sender.baseAddress();
//         byte[] userPkh = user.getBaseAddress().getPaymentCredentialHash().orElseThrow();
//         System.out.println("Sender: " + senderAddr);
//         System.out.println("User PKH: " + HexUtil.encodeHexString(userPkh));

//         // Fetch sender UTxOs
//         var senderUtxos = bs.getUtxoService().getUtxos(senderAddr, 10, 1).getValue();
//         System.out.println("\nSender UTxOs: " + senderUtxos.size());
//         for (var u : senderUtxos) System.out.println("  " + u.getTxHash() + "#" + u.getOutputIndex() + " " + u.getAmount());

//         // Find the head node UTxO (has FSN token)
//         Utxo headUtxo = null;
//         String policyId = null;
//         for (var u : senderUtxos) {
//             for (var a : u.getAmount()) {
//                 if (!a.getUnit().equalsIgnoreCase("lovelace") && a.getUnit().length() > 56) {
//                     String name = a.getUnit().substring(56);
//                     if (name.equalsIgnoreCase(FSN_PREFIX_HEX)) { // exactly "46534e" = head token
//                         headUtxo = u;
//                         policyId = a.getUnit().substring(0, 56);
//                         break;
//                     }
//                 }
//             }
//         }

//         if (headUtxo == null) {
//             System.err.println("ERROR: No head node UTxO found at senderAddr. Run Init first.");
//             System.exit(1);
//         }
//         System.out.println("\nHead UTxO: " + headUtxo.getTxHash() + "#" + headUtxo.getOutputIndex());
//         System.out.println("Policy ID: " + policyId);

//         // Rebuild config — we need to know the init_utxo that was used for Init
//         // The init_utxo's tx_hash comes from the funding tx (index #2 typically)
//         // For debug: we need to find it from the blockchain. Let's look at the head UTxO's creation tx.
//         String initTxHash = headUtxo.getTxHash();
//         int initOutputIndex = 2; // was #2 in the funding tx that created the init UTxO

//         // Actually the init UTxO is the UTxO that was consumed in the Init tx.
//         // We need to look at the Init tx's inputs to find it.
//         // For now let's skip Config rebuild and just print the head UTxO state.
//         System.out.println("\nHead UTxO inline datum: " + headUtxo.getInlineDatum());

//         // Decode the head node datum
//         PlutusData headDatum = PlutusData.deserialize(HexUtil.decodeHexString(headUtxo.getInlineDatum()));
//         System.out.println("Head datum: " + headDatum);

//         // Build the redeemer Insert{userPkh, headNode}
//         // headNode current state from decoded datum
//         ConstrPlutusData headConstr = (ConstrPlutusData) headDatum;
//         byte[] keyBytes = decodeNodeKey((ConstrPlutusData) headConstr.getData().getPlutusDataList().get(0));
//         byte[] nextBytes = decodeNodeKey((ConstrPlutusData) headConstr.getData().getPlutusDataList().get(1));
//         System.out.println("Head key:  " + (keyBytes == null ? "Empty" : HexUtil.encodeHexString(keyBytes)));
//         System.out.println("Head next: " + (nextBytes == null ? "Empty" : HexUtil.encodeHexString(nextBytes)));

//         PlutusData coveringNodeData = buildSetNode(keyBytes, nextBytes);
//         PlutusData redeemer = ConstrPlutusData.of(2, BytesPlutusData.of(userPkh), coveringNodeData);
//         System.out.println("\nRedeemer CBOR: " + HexUtil.encodeHexString(redeemer.serializeToBytes()));

//         String userTokenName = "0x" + FSN_PREFIX_HEX + HexUtil.encodeHexString(userPkh);
//         Asset userAsset = new Asset(userTokenName, BigInteger.ONE);

//         PlutusData updatedCoveringDatum = buildSetNode(keyBytes, userPkh);
//         PlutusData newUserDatum = buildSetNode(userPkh, nextBytes);

//         List<Amount> coveringOutputAmounts = nodeAmounts(headUtxo);
//         List<Amount> newUserNodeAmounts = List.of(
//                 Amount.lovelace(NODE_MIN_LOVELACE),
//                 Amount.asset(policyId, userTokenName, BigInteger.ONE));

//         System.out.println("\nCovering output amounts: " + coveringOutputAmounts);
//         System.out.println("New user node amounts: " + newUserNodeAmounts);
//         System.out.println("Updated covering datum CBOR: " + HexUtil.encodeHexString(updatedCoveringDatum.serializeToBytes()));
//         System.out.println("New user datum CBOR: " + HexUtil.encodeHexString(newUserDatum.serializeToBytes()));

//         // We need the parameterized script. Let's load it from the blueprint.
//         // We need to find the init_utxo. Let's look at the Init tx's input via the
//         // transaction details endpoint.
//         var initTxDetail = bs.getTransactionService().getTransaction(initTxHash).getValue();
//         System.out.println("\nInit tx (" + initTxHash + ") details available: " + (initTxDetail != null));

//         // The init UTxO was consumed in the Init tx. It was a UTxO at senderAddr (no tokens).
//         // From the demo run, senderUtxos.get(0) was "15a18f...#2" (index 2 of the funding tx)
//         // Let's look at the tx via the inputs endpoint
//         // We need to find the init UTxO that was consumed in the Init tx.
//         // The demo output showed: "Init UTxO: 15a18f...#2" (index 2 of the funding tx)
//         // We can find this by looking at the Ogmios submit API or just reconstruct from known data.
//         // For now, let's use a simpler approach: fetch the Init tx from the chain
//         // and look at what inputs it consumed.

//         // Use the yaci-devkit's /api/v1/txs endpoint 
//         var client = java.net.http.HttpClient.newHttpClient();
//         var req = java.net.http.HttpRequest.newBuilder()
//                 .uri(java.net.URI.create(BLOCKFROST_URL + "txs/" + initTxHash + "/utxos"))
//                 .build();
//         var response = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
//         System.out.println("\nInit tx UTxOs: " + response.body().substring(0, Math.min(500, response.body().length())));

//         // Parse the first input from the JSON response
//         String body = response.body();
//         String initUtxoTxHash = null;
//         int initUtxoIndex = 0;
//         // Simple JSON parsing - find first "tx_hash" under "inputs"
//         int inputsIdx = body.indexOf("\"inputs\"");
//         if (inputsIdx >= 0) {
//             int txHashIdx = body.indexOf("\"tx_hash\"", inputsIdx);
//             if (txHashIdx >= 0) {
//                 int start = body.indexOf("\"", txHashIdx + 10) + 1;
//                 int end = body.indexOf("\"", start);
//                 initUtxoTxHash = body.substring(start, end);
//                 int outIdxPos = body.indexOf("\"output_index\"", txHashIdx);
//                 if (outIdxPos >= 0) {
//                     int numStart = body.indexOf(":", outIdxPos) + 1;
//                     while (Character.isWhitespace(body.charAt(numStart))) numStart++;
//                     int numEnd = numStart;
//                     while (numEnd < body.length() && Character.isDigit(body.charAt(numEnd))) numEnd++;
//                     initUtxoIndex = Integer.parseInt(body.substring(numStart, numEnd));
//                 }
//             }
//         }
//         System.out.println("Init UTxO (from Init tx): " + initUtxoTxHash + "#" + initUtxoIndex);

//         PlutusData configData = buildConfig(initUtxoTxHash, initUtxoIndex, 4_070_908_800_000L, senderAddr);
//         System.out.println("\nConfig CBOR: " + HexUtil.encodeHexString(configData.serializeToBytes()));

//         ListPlutusData params = ListPlutusData.of(configData);
//         File blueprintFile = new File("plutus.json").exists() ? new File("plutus.json") : new File("../plutus.json");
//         PlutusContractBlueprint blueprint = PlutusBlueprintLoader.loadBlueprint(blueprintFile);
//         String parameterizedCode = AikenScriptUtil.applyParamToScript(params, blueprint.getValidators().getFirst().getCompiledCode());
//         PlutusV3Script mintScript = (PlutusV3Script) PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(parameterizedCode, PlutusVersion.v3);

//         System.out.println("Policy ID from script: " + mintScript.getPolicyId());
//         System.out.println("Expected policy ID:    " + policyId);
//         System.out.println("Match: " + mintScript.getPolicyId().equals(policyId));

//         if (!mintScript.getPolicyId().equals(policyId)) {
//             System.err.println("\nERROR: Policy ID mismatch! The init UTxO used for Config is wrong.");
//             System.err.println("The head UTxO has policy: " + policyId);
//             System.err.println("But the script parameterized with this init UTxO has policy: " + mintScript.getPolicyId());
//         } else {
//             System.out.println("\nPolicy ID matches! Config is correct.");
//         }

//         // Fund user if needed
//         var userUtxos = bs.getUtxoService().getUtxos(user.baseAddress(), 5, 1).getValue();
//         System.out.println("\nUser UTxOs: " + (userUtxos == null ? "none" : userUtxos.size()));
//     }

//     static PlutusData buildConfig(String txHash, int outputIndex, long deadlineMs, String penaltyBech32Addr) {
//         PlutusData outputRef = ConstrPlutusData.of(0,
//                 BytesPlutusData.of(HexUtil.decodeHexString(txHash)),
//                 BigIntPlutusData.of(outputIndex));
//         PlutusData deadline = BigIntPlutusData.of(deadlineMs);
//         byte[] vkh = new com.bloxbean.cardano.client.address.Address(penaltyBech32Addr)
//                 .getPaymentCredentialHash().orElseThrow();
//         PlutusData paymentCred = ConstrPlutusData.of(0, BytesPlutusData.of(vkh));
//         PlutusData stakeNone = ConstrPlutusData.of(1);
//         PlutusData address = ConstrPlutusData.of(0, paymentCred, stakeNone);
//         return ConstrPlutusData.of(0, outputRef, deadline, address);
//     }

//     static PlutusData buildSetNode(byte[] keyBytes, byte[] nextBytes) {
//         PlutusData key  = keyBytes  == null ? ConstrPlutusData.of(1) : ConstrPlutusData.of(0, BytesPlutusData.of(keyBytes));
//         PlutusData next = nextBytes == null ? ConstrPlutusData.of(1) : ConstrPlutusData.of(0, BytesPlutusData.of(nextBytes));
//         return ConstrPlutusData.of(0, key, next);
//     }

//     static byte[] decodeNodeKey(ConstrPlutusData nodeKey) {
//         if (nodeKey.getAlternative() == 1) return null;
//         var fields = nodeKey.getData().getPlutusDataList();
//         return ((BytesPlutusData) fields.get(0)).getValue();
//     }

//     static List<Amount> nodeAmounts(Utxo utxo) {
//         return utxo.getAmount().stream().map(a -> {
//             if (a.getUnit().equalsIgnoreCase("lovelace")) return Amount.lovelace(a.getQuantity());
//             String policy = a.getUnit().substring(0, 56);
//             String hexName = a.getUnit().substring(56);
//             return Amount.asset(policy, "0x" + hexName, a.getQuantity());
//         }).collect(Collectors.toList());
//     }
// }
