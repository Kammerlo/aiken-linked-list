package offchain;
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS com.bloxbean.cardano:cardano-client-lib:0.7.1
//DEPS com.bloxbean.cardano:cardano-client-backend-blockfrost:0.7.1
//DEPS com.bloxbean.cardano:aiken-java-binding:0.1.0
//DEPS org.slf4j:slf4j-simple:2.0.9

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintLoader;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusContractBlueprint;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.quicktx.TxResult; // used in fundBothAccounts
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;

import java.io.File;
import java.math.BigInteger;
import java.util.List;

/**
 * JBang script demonstrating off-chain interaction with the Aiken Linked List validator.
 *
 * Prerequisites:
 *   - Local Blockfrost-compatible API at http://localhost:8080/api/v1/ (e.g. yaci-devkit)
 *   - The validator has NOT yet been deployed; this script handles Init → Insert x2 → Remove
 *
 * Usage:
 *   jbang LinkedListDemo.java
 *
 * ────────────────────────────────────────────────────────────
 * Plutus blueprint key facts (from plutus.json):
 *   Validator title : sample.sample.mint
 *   Plutus version  : v3
 *   Unparameterized hash : 9238a3570629500060641b42ffa29193f46d193e59b668afd0f01d41
 *   The validator is parameterized with `cfg: Config`, so it must have the
 *   Config applied before the script hash / address can be calculated.
 *
 * On-chain type encoding (Plutus Data constr indices from plutus.json):
 *
 *   Config          = Constr(0, [OutputReference, Int/deadline, Address])
 *   OutputReference = Constr(0, [Constr(0, [txHashBytes]), outputIndex])
 *   Address         = Constr(0, [PaymentCredential, Option<StakeCredential>])
 *   VerificationKey = Constr(0, [vkh_bytes])   (PaymentCredential)
 *   None            = Constr(1, [])             (Option)
 *
 *   SetNode         = Constr(0, [NodeKey, NodeKey])
 *   NodeKey/Empty   = Constr(1, [])
 *   NodeKey/Key     = Constr(0, [pkh_bytes])
 *
 *   NodeAction/Init    = Constr(0, [])
 *   NodeAction/Deinit  = Constr(1, [])
 *   NodeAction/Insert  = Constr(2, [pkh_bytes, SetNode])
 *   NodeAction/Remove  = Constr(3, [pkh_bytes, SetNode])
 *
 * Token naming:
 *   Head/origin token  : "FSN"  (bytes: 0x46534e)
 *   User node token    : "FSN" + <raw pkh bytes>
 * ────────────────────────────────────────────────────────────
 */
public class LinkedListDemo {

    // ── Configuration – adjust these values for your local setup ───────────
    static final String BLOCKFROST_URL = "http://localhost:8080/api/v1/";
    static final String BLOCKFROST_PROJECT_ID = "localnet"; // any non-empty value for yaci-devkit

    /**
     * Sender mnemonic – this account pays fees and acts as the "admin".
     * Replace with an account funded on your local devnet.
     */
    static final String SENDER_MNEMONIC =
            "test test test test test test test test test test test test " +
            "test test test test test test test test test test test sauce";

    /**
     * Deadline (POSIX milliseconds). Set well in the future so Insert/Remove are allowed.
     * Default: year 2099 ≈ 4070908800000 ms
     */
    static final long DEADLINE_POSIX_MS = 4_070_908_800_000L;

    /**
     * Minimum ADA to lock with each node UTxO (in lovelace).
     */
    static final BigInteger NODE_MIN_LOVELACE = BigInteger.valueOf(2_000_000);

    // ── Token naming constants (mirror on-chain constants.ak) ───────────────
    static final String FSN_PREFIX     = "FSN";    // raw UTF-8 string
    static final String FSN_PREFIX_HEX = "46534e"; // hex-encoded bytes of "FSN"

    // ── main ───────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        System.out.println("=== Aiken Linked List Demo ===\n");

        // ── 1. Setup ──────────────────────────────────────────────────────
        BackendService backendService = new BFBackendService(BLOCKFROST_URL, BLOCKFROST_PROJECT_ID);
        QuickTxBuilder quickTxBuilder = new QuickTxBuilder(backendService);

        Account sender = Account.createFromMnemonic(Networks.testnet(), SENDER_MNEMONIC);
        Account user1  = new Account(Networks.testnet());
        Account user2  = new Account(Networks.testnet());

        String senderAddr = sender.baseAddress();
        System.out.println("Sender address : " + senderAddr);
        System.out.println("User1  address : " + user1.baseAddress());
        System.out.println("User2  address : " + user2.baseAddress());

        // Payment-credential hash = verification-key hash (28 bytes)
        byte[] user1Pkh = user1.getBaseAddress().getPaymentCredentialHash().orElseThrow();
        byte[] user2Pkh = user2.getBaseAddress().getPaymentCredentialHash().orElseThrow();
        System.out.println("User1  PKH     : " + HexUtil.encodeHexString(user1Pkh));
        System.out.println("User2  PKH     : " + HexUtil.encodeHexString(user2Pkh));

        // Fund user accounts only if they don't yet have enough ADA
        System.out.println("\n--- Funding user accounts ---");
        boolean user1NeedsFunding = needsFunding(backendService, user1.baseAddress(), 5_000_000L);
        boolean user2NeedsFunding = needsFunding(backendService, user2.baseAddress(), 5_000_000L);
        if (user1NeedsFunding || user2NeedsFunding) {
            List<Account> toFund = new java.util.ArrayList<>();
            if (user1NeedsFunding) { toFund.add(user1); System.out.println("  Funding user1"); }
            if (user2NeedsFunding) { toFund.add(user2); System.out.println("  Funding user2"); }
            String fundTxHash = fundBothAccounts(quickTxBuilder, sender, Amount.ada(10),
                    toFund.toArray(new Account[0]));
            waitForTx(backendService, fundTxHash, senderAddr);
            System.out.println("  ✓ User accounts funded (10 ADA each)");
        } else {
            System.out.println("  ✓ User accounts already funded, skipping");
        }

        // ── 2. Load blueprint and find/build the parameterized script ──────
        // We need the script to compute the policyId in order to check whether
        // the head node already exists. We use a "probe" UTxO just for this lookup;
        // if we actually need to Init we will pick the real initUtxo then.
        System.out.println("\n--- Loading blueprint ---");
        File blueprintFile = new File("../plutus.json");
        System.out.println("Blueprint file : " + blueprintFile.getAbsolutePath());
        PlutusContractBlueprint blueprint = PlutusBlueprintLoader.loadBlueprint(blueprintFile);

        // ── 3. INIT (idempotent) ──────────────────────────────────────────
        System.out.println("\n=== Step 1: INIT ===");

        // Pick initUtxo and build Config; if already initialised we skip this
        // but we still need a stable policyId → load from existing head node or
        // compute it from a fresh initUtxo.
        List<Utxo> senderUtxos = backendService.getUtxoService().getUtxos(senderAddr, 20, 1).getValue();
        if (senderUtxos == null || senderUtxos.isEmpty()) {
            System.err.println("ERROR: No UTxOs found at sender address. Please fund: " + senderAddr);
            System.exit(1);
        }

        // Try to discover an existing init UTxO by scanning sender UTxOs for one
        // whose Config-derived policy already has a head token at senderAddr.
        // We use the FIRST plain-lovelace-only UTxO as the candidate initUtxo.
        Utxo initUtxo = senderUtxos.stream()
                .filter(u -> u.getAmount() != null &&
                        u.getAmount().stream().allMatch(a -> a.getUnit().equalsIgnoreCase("lovelace")))
                .findFirst()
                .orElse(senderUtxos.get(0));
        System.out.println("Init UTxO      : " + initUtxo.getTxHash() + "#" + initUtxo.getOutputIndex());

        PlutusData configData = buildConfig(
                initUtxo.getTxHash(), initUtxo.getOutputIndex(),
                DEADLINE_POSIX_MS,
                senderAddr
        );
        ListPlutusData params = ListPlutusData.of(configData);
        String parameterizedCode = AikenScriptUtil.applyParamToScript(
                params, blueprint.getValidators().getFirst().getCompiledCode());
        PlutusScript mintScript = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                parameterizedCode, PlutusVersion.v3);
        String policyId = mintScript.getPolicyId();
        System.out.println("Parameterized policy ID : " + policyId);
        String scriptAddress = AddressProvider.getEntAddress(mintScript, Networks.testnet()).toBech32();
        System.out.println("Script address (ref)    : " + scriptAddress);

        // Check whether the head node (key=Empty, next=*) already exists
        boolean alreadyInitialised = findHeadNode(backendService, senderAddr, policyId) != null;
        if (alreadyInitialised) {
            System.out.println("  ✓ Linked list already initialised, skipping Init");
        } else {
            String initTxHash = doInit(quickTxBuilder, sender, senderAddr,
                    mintScript, policyId, initUtxo, scriptAddress);
            waitForTx(backendService, initTxHash, senderAddr);
        }

        // ── 5. INSERT user1 ───────────────────────────────────────────────
        System.out.println("\n=== Step 2: INSERT user1 ===");
        if (findNodeByPkh(backendService, senderAddr, policyId, user1Pkh) != null) {
            System.out.println("  ✓ User1 node already exists, skipping Insert");
        } else {
            String insertUser1TxHash = doInsert(quickTxBuilder, sender, user1,
                    senderAddr, senderAddr, mintScript, policyId,
                    user1Pkh, backendService);
            waitForTx(backendService, insertUser1TxHash, senderAddr);
        }

        // ── 6. INSERT user2 ───────────────────────────────────────────────
        System.out.println("\n=== Step 3: INSERT user2 ===");
        if (findNodeByPkh(backendService, senderAddr, policyId, user2Pkh) != null) {
            System.out.println("  ✓ User2 node already exists, skipping Insert");
        } else {
            String insertUser2TxHash = doInsert(quickTxBuilder, sender, user2,
                    senderAddr, senderAddr, mintScript, policyId,
                    user2Pkh, backendService);
            waitForTx(backendService, insertUser2TxHash, senderAddr);
        }

        // ── 7. REMOVE user1 ───────────────────────────────────────────────
        System.out.println("\n=== Step 4: REMOVE user1 ===");
        if (findNodeByPkh(backendService, senderAddr, policyId, user1Pkh) == null) {
            System.out.println("  ✓ User1 node already removed, skipping Remove");
        } else {
            String removeTxHash = doRemove(quickTxBuilder, sender, user1,
                    senderAddr, mintScript, policyId,
                    user1Pkh, backendService);
            waitForTx(backendService, removeTxHash, senderAddr);
        }

        // ── 8. REMOVE user2 ───────────────────────────────────────────────
        System.out.println("\n=== Step 5: REMOVE user2 ===");
        if (findNodeByPkh(backendService, senderAddr, policyId, user2Pkh) == null) {
            System.out.println("  ✓ User2 node already removed, skipping Remove");
        } else {
            String removeUser2TxHash = doRemove(quickTxBuilder, sender, user2,
                    senderAddr, mintScript, policyId,
                    user2Pkh, backendService);
            waitForTx(backendService, removeUser2TxHash, senderAddr);
        }

        // ── 9. DEINIT ─────────────────────────────────────────────────────
        // Burns the FSN head token and returns the 2 ADA locked in it to the sender.
        // Only valid when the list is empty (head node next = Empty).
        System.out.println("\n=== Step 6: DEINIT ===");
        Utxo headNodeForDeinit = findHeadNode(backendService, senderAddr, policyId);
        if (headNodeForDeinit == null) {
            System.out.println("  ✓ Already deinited, skipping");
        } else {
            SetNodeInfo headInfo = decodeSetNodeDatum(headNodeForDeinit);
            if (headInfo.nextBytes != null) {
                System.out.println("  WARNING: List not empty (next=" +
                        keyToString(headInfo.nextBytes) + "), skipping Deinit");
            } else {
                String deinitTxHash = doDeinit(quickTxBuilder, sender, senderAddr,
                        mintScript, policyId, headNodeForDeinit);
                waitForTx(backendService, deinitTxHash, senderAddr);
            }
        }

        System.out.println("\n=== Done ===");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Step 1 – Init: mint the head/origin "FSN" token, produce head node UTxO
    // ──────────────────────────────────────────────────────────────────────
    static String doInit(QuickTxBuilder quickTxBuilder,
                         Account sender,
                         String senderAddr,
                         PlutusScript mintScript,
                         String policyId,
                         Utxo initUtxo,
                         String scriptAddress) throws Exception {

        // Redeemer: Init = Constr(0, [])
        PlutusData redeemer = ConstrPlutusData.of(0);

        // Datum for the head node: SetNode { key: Empty, next: Empty }
        PlutusData headDatum = buildSetNode(null, null);

        // Token name: use "0x" + hexString so Asset.nameToBytes() hex-decodes
        // to the 3 raw bytes 0x46534e. Passing the plain string "FSN" also works
        // (UTF-8 = same 3 bytes) but "0x" prefix is consistent with node tokens.
        String headTokenName = "0x" + FSN_PREFIX_HEX;  // "0x46534e"
        Asset headAsset = new Asset(headTokenName, BigInteger.ONE);

        // Single combined output: lovelace + FSN token + inline datum
        // Nodes live at senderAddr – else(_){fail} blocks spending from the script address
        List<Amount> headNodeAmounts = List.of(
                Amount.lovelace(NODE_MIN_LOVELACE),
                Amount.asset(policyId, headTokenName, BigInteger.ONE));

        ScriptTx scriptTx = new ScriptTx()
                .collectFrom(initUtxo)                         // consumes init UTxO (one-shot)
                .mintAsset(mintScript, headAsset, redeemer)    // 3-arg: no separate output
                .payToContract(senderAddr, headNodeAmounts, headDatum)
                
                .withChangeAddress(senderAddr);

        Result<String> result = quickTxBuilder
                .compose(scriptTx)
                .mergeOutputs(false)
                .feePayer(senderAddr)
                .withSigner(SignerProviders.signerFrom(sender))
                .completeAndWait(msg -> System.out.println("  " + msg));

        checkResult(result, "Init");
        return result.getValue();
    }

    /** Fund two accounts in a single transaction to avoid UTxO double-spend races. */
    static String fundBothAccounts(QuickTxBuilder quickTxBuilder, Account sender,
                                    Amount amount, Account... receivers) {
        Tx tx = new Tx()
                .from(sender.baseAddress())
                .withChangeAddress(sender.baseAddress());
        for(Account receiver : receivers) {
            tx = tx.payToAddress(receiver.baseAddress(), amount);
        }
        TxResult result = quickTxBuilder.compose(tx)
                .feePayer(sender.baseAddress())
                .withSigner(SignerProviders.signerFrom(sender))
                .completeAndWait(msg -> System.out.println("  " + msg));
        checkResult(result, "Fund users");
        return result.getValue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Step 2/3 – Insert: mint "FSN<pkh>", consume covering node, produce two nodes
    // ──────────────────────────────────────────────────────────────────────
    static String doInsert(QuickTxBuilder quickTxBuilder,
                           Account sender,
                           Account user,
                           String senderAddr,
                           String scriptAddr,
                           PlutusScript mintScript,
                           String policyId,
                           byte[] userPkh,
                           BackendService backendService) throws Exception {

        // Nodes live at senderAddr (not the script's enterprise address)
        Utxo coveringUtxo = findCoveringNode(backendService, scriptAddr, policyId, userPkh);
        if (coveringUtxo == null) {
            throw new RuntimeException("Could not find a covering node for pkh: " +
                    HexUtil.encodeHexString(userPkh));
        }

        System.out.println("  Covering UTxO: " + coveringUtxo.getTxHash() + "#" + coveringUtxo.getOutputIndex());

        // Decode the covering node's inline datum
        SetNodeInfo coveringInfo = decodeSetNodeDatum(coveringUtxo);
        System.out.println("  Covering node key : " + keyToString(coveringInfo.keyBytes));
        System.out.println("  Covering node next: " + keyToString(coveringInfo.nextBytes));

        // Updated covering node: key stays the same, next = userPkh
        PlutusData updatedCoveringDatum = buildSetNode(coveringInfo.keyBytes, userPkh);

        // New user node: key = userPkh, next = original next
        PlutusData newUserDatum = buildSetNode(userPkh, coveringInfo.nextBytes);

        // Redeemer's covering_node = CURRENT (pre-insert) state of covering node
        PlutusData coveringNodeData = buildSetNode(coveringInfo.keyBytes, coveringInfo.nextBytes);
        PlutusData redeemer = ConstrPlutusData.of(2,
                BytesPlutusData.of(userPkh),
                coveringNodeData);

        // Token name for new user node: hex("FSN" + pkh bytes)
        String userTokenName = buildNodeTokenName(userPkh);
        Asset userAsset = new Asset(userTokenName, BigInteger.ONE);

        // Output amounts:
        //   covering node → preserve ALL its amounts (lovelace + its own policy token)
        //   new user node → lovelace + newly minted user token
        List<Amount> coveringOutputAmounts = nodeAmounts(coveringUtxo);
        List<Amount> newUserNodeAmounts = List.of(
                Amount.lovelace(NODE_MIN_LOVELACE),
                Amount.asset(policyId, userTokenName, BigInteger.ONE));

        // Insert/Remove validators call is_entirely_before(vrange, cfg.deadline),
        // which requires a finite upper validity bound (TTL) on the transaction.
        // validFrom/validTo take slot numbers; CCL converts to POSIX ms for the script context.
        long currentSlot = getCurrentSlot(backendService);

        ScriptTx scriptTx = new ScriptTx()
                .collectFrom(coveringUtxo)              // no redeemer – at sender's addr
                .mintAsset(mintScript, userAsset, redeemer)
                .payToContract(scriptAddr, coveringOutputAmounts, updatedCoveringDatum)
                .payToContract(scriptAddr, newUserNodeAmounts, newUserDatum)
                // Change address must be user's address (NOT senderAddr) so that the
                // -2 ADA "shortfall" from createFromUtxos ends up at user.baseAddress()
                // where ChangeOutputAdjustments can find and fix it by adding the user's UTxO.
                .withChangeAddress(user.baseAddress());

        var txContext = quickTxBuilder
                .compose(scriptTx)
                .mergeOutputs(false)                             // keep covering + user node outputs separate
                .feePayer(user.baseAddress())                    // user pays fees + provides collateral
                .withSigner(SignerProviders.signerFrom(sender))  // sender controls covering node UTxO
                .withSigner(SignerProviders.signerFrom(user))    // user must sign Insert
                .withRequiredSigners(user.getBaseAddress())
                .validFrom(0)
                .validTo(currentSlot + 200)
                .withTxInspector(tx -> {
                    System.out.println("  === TX INSPECTOR (Insert) ===");
                    System.out.println("  Inputs (" + tx.getBody().getInputs().size() + "):");
                    tx.getBody().getInputs().forEach(i -> System.out.println("    " + i.getTransactionId() + "#" + i.getIndex()));
                    System.out.println("  Outputs (" + tx.getBody().getOutputs().size() + "):");
                    tx.getBody().getOutputs().forEach(o -> System.out.println(
                            "    addr=" + o.getAddress().substring(0, Math.min(40, o.getAddress().length())) +
                            " val=" + o.getValue() +
                            " datum=" + (o.getInlineDatum() != null ? "inline" : o.getDatumHash() != null ? "hash" : "none")));
                    System.out.println("  Mint: " + tx.getBody().getMint());
                    System.out.println("  TTL: " + tx.getBody().getTtl());
                    System.out.println("  ValidityStart: " + tx.getBody().getValidityStartInterval());
                    if (tx.getBody().getRequiredSigners() != null)
                        System.out.println("  RequiredSigners: " + tx.getBody().getRequiredSigners().stream()
                                .map(HexUtil::encodeHexString).collect(java.util.stream.Collectors.toList()));
                
                    System.out.println("  === END TX ===");
                });

        Result<String> result;
        try {
            result = txContext.completeAndWait(msg -> System.out.println("  " + msg));
        } catch (com.bloxbean.cardano.client.function.exception.TxBuildException ex) {
            System.err.println("  TxBuildException: " + ex.getMessage().substring(0, Math.min(500, ex.getMessage().length())));
            throw new RuntimeException("Insert failed: " + ex.getMessage(), ex);
        }

        checkResult(result, "Insert " + HexUtil.encodeHexString(userPkh));
        return result.getValue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Step 4 – Remove: burn "FSN<user1Pkh>", consume covering + user1 node
    // ──────────────────────────────────────────────────────────────────────
    static String doRemove(QuickTxBuilder quickTxBuilder,
                           Account sender,
                           Account user,
                           String senderAddr,
                           PlutusScript mintScript,
                           String policyId,
                           byte[] removeUserPkh,
                           BackendService backendService) throws Exception {

        // Nodes live at senderAddr
        Utxo removeUtxo = findNodeByPkh(backendService, senderAddr, policyId, removeUserPkh);
        if (removeUtxo == null) {
            throw new RuntimeException("Cannot find node to remove for pkh: " +
                    HexUtil.encodeHexString(removeUserPkh));
        }
        System.out.println("  Remove UTxO  : " + removeUtxo.getTxHash() + "#" + removeUtxo.getOutputIndex());

        // For Remove, the "covering" node is the predecessor whose current next == remove_key
        Utxo coveringUtxo = findPredecessorNode(backendService, senderAddr, policyId, removeUserPkh);
        if (coveringUtxo == null) {
            throw new RuntimeException("Cannot find covering node for remove pkh: " +
                    HexUtil.encodeHexString(removeUserPkh));
        }
        System.out.println("  Covering UTxO: " + coveringUtxo.getTxHash() + "#" + coveringUtxo.getOutputIndex());

        SetNodeInfo coveringInfo = decodeSetNodeDatum(coveringUtxo);
        SetNodeInfo removeInfo   = decodeSetNodeDatum(removeUtxo);

        System.out.println("  Covering key  : " + keyToString(coveringInfo.keyBytes));
        System.out.println("  Covering next : " + keyToString(coveringInfo.nextBytes));
        System.out.println("  Remove   key  : " + keyToString(removeInfo.keyBytes));
        System.out.println("  Remove   next : " + keyToString(removeInfo.nextBytes));

        // The Remove redeemer's covering_node must be the POST-REMOVE state:
        //   key = same, next = removed_node.next  (skipping the removed node)
        // This is required because cover_key() checks next > remove_key, and the
        // current covering next == remove_key (equal, not greater).
        PlutusData coveringNodeData = buildSetNode(coveringInfo.keyBytes, removeInfo.nextBytes);
        PlutusData redeemer = ConstrPlutusData.of(3,
                BytesPlutusData.of(removeUserPkh),
                coveringNodeData);

        // The output datum for the covering node equals the redeemer's covering_node
        PlutusData updatedCoveringDatum = coveringNodeData;

        // Token to burn: "FSN" + removeUserPkh
        String removeTokenName = buildNodeTokenName(removeUserPkh);
        Asset burnAsset = new Asset(removeTokenName, BigInteger.ONE.negate());

        // Preserve covering node's exact amounts (lovelace + its policy token)
        List<Amount> coveringOutputAmounts = nodeAmounts(coveringUtxo);

        // Remove also checks is_entirely_before(vrange, cfg.deadline)
        long currentSlot = getCurrentSlot(backendService);

        ScriptTx scriptTx = new ScriptTx()
                .collectFrom(coveringUtxo)               // no redeemer – at sender's addr
                .collectFrom(removeUtxo)                 // no redeemer – at sender's addr
                .mintAsset(mintScript, burnAsset, redeemer)  // 3-arg burn
                .payToContract(senderAddr, coveringOutputAmounts, updatedCoveringDatum)
                .withChangeAddress(user.baseAddress());

        Result<String> result = quickTxBuilder
                .compose(scriptTx)
                .mergeOutputs(false)
                .feePayer(user.baseAddress())                    // user pays fees + provides collateral
                .withSigner(SignerProviders.signerFrom(sender))  // sender controls node UTxOs
                .withSigner(SignerProviders.signerFrom(user))    // remove_key must sign
                .withRequiredSigners(user.getBaseAddress())
                .validFrom(0)
                .validTo(currentSlot + 200)
                .completeAndWait(msg -> System.out.println("  " + msg));

        checkResult(result, "Remove " + HexUtil.encodeHexString(removeUserPkh));
        return result.getValue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Step 6 – Deinit: burn the head FSN token, reclaim the 2 ADA locked in it.
    // Requires the list to be empty (head node next = Empty).
    // ──────────────────────────────────────────────────────────────────────
    static String doDeinit(QuickTxBuilder quickTxBuilder,
                            Account sender,
                            String senderAddr,
                            PlutusScript mintScript,
                            String policyId,
                            Utxo headNode) throws Exception {

        // Redeemer: Deinit = Constr(1, [])
        PlutusData redeemer = ConstrPlutusData.of(1);

        // Burn the origin FSN head token (-1)
        String headTokenName = "0x" + FSN_PREFIX_HEX;
        Asset burnAsset = new Asset(headTokenName, BigInteger.ONE.negate());

        // No payToContract – burning the token; reclaimed ADA goes to sender as change
        ScriptTx scriptTx = new ScriptTx()
                .collectFrom(headNode)
                .mintAsset(mintScript, burnAsset, redeemer)
                .withChangeAddress(senderAddr);

        Result<String> result = quickTxBuilder
                .compose(scriptTx)
                .mergeOutputs(false)
                .feePayer(senderAddr)
                .withSigner(SignerProviders.signerFrom(sender))
                .completeAndWait(msg -> System.out.println("  " + msg));

        checkResult(result, "Deinit");
        return result.getValue();
    }

    // ══════════════════════════════════════════════════════════════════════
    // PlutusData builders
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Build Config PlutusData.
     * Config = Constr(0, [OutputReference, Int/deadline, Address])
     * OutputReference = Constr(0, [Constr(0, [txHashBytes]), outputIndex])
     * Address = Constr(0, [PaymentCredential, Option<StakeCredential>])
     * PaymentCredential (VerificationKey) = Constr(0, [vkh_bytes])
     * Option None = Constr(1, [])
     */
    static PlutusData buildConfig(String txHash, int outputIndex,
                                  long deadlineMs, String penaltyBech32Addr) {
        // OutputReference
        // PlutusData txHashData = ConstrPlutusData.of(0,
        //         BytesPlutusData.of(HexUtil.decodeHexString(txHash)));
        PlutusData outputRef = ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(txHash)),
                BigIntPlutusData.of(outputIndex));

        // deadline
        PlutusData deadline = BigIntPlutusData.of(deadlineMs);

        // penalty_address (VerificationKey credential, no stake)
        byte[] vkh = new com.bloxbean.cardano.client.address.Address(penaltyBech32Addr)
                .getPaymentCredentialHash().orElseThrow();
        PlutusData paymentCred = ConstrPlutusData.of(0, BytesPlutusData.of(vkh));
        PlutusData stakeNone   = ConstrPlutusData.of(1); // None
        PlutusData address     = ConstrPlutusData.of(0, paymentCred, stakeNone);

        return ConstrPlutusData.of(0, outputRef, deadline, address);
    }

    /**
     * Build SetNode PlutusData.
     * SetNode = Constr(0, [NodeKey, NodeKey])
     * NodeKey/Empty = Constr(1, [])
     * NodeKey/Key   = Constr(0, [pkh_bytes])
     *
     * Pass null for Empty, byte[] for Key.
     */
    static PlutusData buildSetNode(byte[] keyBytes, byte[] nextBytes) {
        PlutusData key  = keyBytes  == null ? ConstrPlutusData.of(1) : ConstrPlutusData.of(0, BytesPlutusData.of(keyBytes));
        PlutusData next = nextBytes == null ? ConstrPlutusData.of(1) : ConstrPlutusData.of(0, BytesPlutusData.of(nextBytes));
        return ConstrPlutusData.of(0, key, next);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Token naming helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Node token name = "FSN" (3 bytes 0x46534e) + raw pkh bytes (28 bytes) = 31 bytes.
     *
     * CCL's Asset.nameToBytes(String) logic:
     *   - If the string starts with "0x" → hex-decode the remainder
     *   - Otherwise                      → use UTF-8 bytes directly
     *
     * We MUST use the "0x" + hexString form here because the 28 PKH bytes can
     * contain values > 0x7F, which UTF-8 would inflate to 2 bytes each, producing
     * a name > 32 bytes and failing Conway CBOR validation.
     * The "0x" prefix tells CCL to hex-decode, giving exactly 31 raw bytes.
     */
    static String buildNodeTokenName(byte[] pkh) {
        return "0x" + FSN_PREFIX_HEX + HexUtil.encodeHexString(pkh);
    }

    // ══════════════════════════════════════════════════════════════════════
    // UTxO / datum helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Find the covering node for a given pkh.
     * A covering node has: its key <= pkh and its next > pkh (or next = Empty).
     * We look through all UTxOs at the script address that carry a node token.
     */
    static Utxo findCoveringNode(BackendService backendService, String scriptAddress,
                                  String policyId, byte[] pkh) throws ApiException {
        List<Utxo> utxos = getAllScriptUtxos(backendService, scriptAddress, policyId);
        for (Utxo utxo : utxos) {
            SetNodeInfo info = decodeSetNodeDatum(utxo);
            if (coversKey(info, pkh)) {
                return utxo;
            }
        }
        return null;
    }

    /**
     * Find the predecessor node for a Remove: the node whose current next == pkh.
     * (Unlike the Insert covering node which needs next > pkh strictly.)
     */
    static Utxo findPredecessorNode(BackendService backendService, String scriptAddress,
                                     String policyId, byte[] pkh) throws ApiException {
        List<Utxo> utxos = getAllScriptUtxos(backendService, scriptAddress, policyId);
        for (Utxo utxo : utxos) {
            SetNodeInfo info = decodeSetNodeDatum(utxo);
            if (info.nextBytes != null && java.util.Arrays.equals(info.nextBytes, pkh)) {
                return utxo;
            }
        }
        return null;
    }

    /**
     * Find the specific node UTxO whose key == pkh.
     */
    static Utxo findNodeByPkh(BackendService backendService, String scriptAddress,
                               String policyId, byte[] pkh) throws ApiException {
        List<Utxo> utxos = getAllScriptUtxos(backendService, scriptAddress, policyId);
        for (Utxo utxo : utxos) {
            SetNodeInfo info = decodeSetNodeDatum(utxo);
            if (info.keyBytes != null && java.util.Arrays.equals(info.keyBytes, pkh)) {
                return utxo;
            }
        }
        return null;
    }

    /**
     * Returns true if the address has less than minLovelace in total ADA.
     * Used to decide whether funding is needed before the demo steps.
     */
    static boolean needsFunding(BackendService backendService, String address,
                                 long minLovelace) throws ApiException {
        List<Utxo> utxos = backendService.getUtxoService().getUtxos(address, 50, 1).getValue();
        if (utxos == null || utxos.isEmpty()) return true;
        long total = utxos.stream()
                .flatMap(u -> u.getAmount().stream())
                .filter(a -> a.getUnit().equalsIgnoreCase("lovelace"))
                .mapToLong(a -> a.getQuantity().longValue())
                .sum();
        return total < minLovelace;
    }

    /**
     * Find the head node UTxO: the node carrying the plain "FSN" (origin) token
     * with key=Empty and next=* at the given address.
     * Returns null if the list has not been initialised yet.
     */
    static Utxo findHeadNode(BackendService backendService, String address,
                              String policyId) throws ApiException {
        String headUnit = policyId + FSN_PREFIX_HEX; // exactly 56+6 = 62 chars
        List<Utxo> utxos = backendService.getUtxoService().getUtxos(address, 50, 1).getValue();
        if (utxos == null) return null;
        return utxos.stream()
                .filter(u -> u.getAmount() != null && u.getAmount().stream()
                        .anyMatch(a -> a.getUnit().equalsIgnoreCase(headUnit)))
                .findFirst().orElse(null);
    }

    /** Fetch all UTxOs at the script address that contain the policy's tokens. */
    static List<Utxo> getAllScriptUtxos(BackendService backendService, String scriptAddress,
                                         String policyId) throws ApiException {
        List<Utxo> all = backendService.getUtxoService().getUtxos(scriptAddress, 50, 1).getValue();
        if (all == null) return List.of();
        return all.stream()
                .filter(u -> u.getAmount() != null &&
                        u.getAmount().stream().anyMatch(a -> a.getUnit().startsWith(policyId)))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Decode a SetNode datum from a UTxO's inline datum.
     * The datum is stored as CBOR inline datum; cardano-client-lib provides it
     * as a hex string in Utxo.getInlineDatum().
     */
    static SetNodeInfo decodeSetNodeDatum(Utxo utxo) {
        String inlineDatumHex = utxo.getInlineDatum();
        if (inlineDatumHex == null || inlineDatumHex.isEmpty()) {
            throw new RuntimeException("UTxO " + utxo.getTxHash() + "#" + utxo.getOutputIndex() +
                    " has no inline datum");
        }
        // Deserialize CBOR → PlutusData
        PlutusData datum;
        try {
            datum = PlutusData.deserialize(HexUtil.decodeHexString(inlineDatumHex));
        } catch (CborDeserializationException e) {
            throw new RuntimeException("Failed to deserialize CBOR datum for UTxO " +
                    utxo.getTxHash() + "#" + utxo.getOutputIndex(), e);
        }

        // SetNode = Constr(0, [NodeKey, NodeKey])
        if (!(datum instanceof ConstrPlutusData constr)) {
            throw new RuntimeException("Expected ConstrPlutusData for SetNode");
        }
        var fields = constr.getData().getPlutusDataList();
        byte[] keyBytes  = decodeNodeKey((ConstrPlutusData) fields.get(0));
        byte[] nextBytes = decodeNodeKey((ConstrPlutusData) fields.get(1));
        return new SetNodeInfo(keyBytes, nextBytes);
    }

    /** NodeKey/Empty → null, NodeKey/Key → byte[] */
    static byte[] decodeNodeKey(ConstrPlutusData nodeKey) {
        if (nodeKey.getAlternative() == 1) return null; // Empty
        var fields = nodeKey.getData().getPlutusDataList();
        return ((BytesPlutusData) fields.get(0)).getValue();
    }

    /**
     * Does this node cover the given pkh?
     * key < pkh AND (next == null || next > pkh)
     * HEAD (key=null) covers everything where next > pkh or next==null.
     */
    static boolean coversKey(SetNodeInfo node, byte[] pkh) {
        // key side: Empty always "less" than anything; otherwise compare
        boolean keyOk;
        if (node.keyBytes == null) {
            keyOk = true; // Empty < pkh always
        } else {
            keyOk = compare(node.keyBytes, pkh) < 0;
        }
        if (!keyOk) return false;

        // next side: Empty always "greater"; otherwise compare
        boolean nextOk;
        if (node.nextBytes == null) {
            nextOk = true; // Empty > pkh always
        } else {
            nextOk = compare(node.nextBytes, pkh) > 0;
        }
        return nextOk;
    }

    static int compare(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int diff = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
            if (diff != 0) return diff;
        }
        return Integer.compare(a.length, b.length);
    }

    /**
     * Returns all amounts from a UTxO (lovelace + any native assets) as List<Amount>.
     * Used to preserve the exact value of node UTxOs in payToContract outputs.
     *
     * The chain API (yaci-devkit / Blockfrost) returns native-token units as
     *   policyId(56 hex chars) + assetNameHex
     * CCL's Amount processing uses simple substring(56) to extract the asset name,
     * then calls Asset.nameToBytes(name). Without a "0x" prefix, nameToBytes uses
     * UTF-8 bytes of the hex string (e.g. "46534e" → 6 wrong bytes). Prefixing the
     * name with "0x" triggers hex-decode → the correct raw bytes.
     */
    static List<Amount> nodeAmounts(Utxo utxo) {
        return utxo.getAmount().stream()
                .map(a -> {
                    if (a.getUnit().equalsIgnoreCase("lovelace")) {
                        return Amount.lovelace(a.getQuantity());
                    }
                    // unit = policyId(56 hex chars) + assetNameHex (Blockfrost format)
                    String unit = a.getUnit();
                    String policy   = unit.substring(0, 56);
                    String hexName  = unit.substring(56);
                    // "0x" prefix → nameToBytes hex-decodes → correct raw bytes
                    return Amount.asset(policy, "0x" + hexName, a.getQuantity());
                })
                .collect(java.util.stream.Collectors.toList());
    }

    static String keyToString(byte[] pkh) {
        return pkh == null ? "Empty" : HexUtil.encodeHexString(pkh);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Utility
    // ══════════════════════════════════════════════════════════════════════

    /** Return the slot number of the latest block on chain. */
    static long getCurrentSlot(BackendService backendService) throws ApiException {
        var block = backendService.getBlockService().getLatestBlock().getValue();
        long slot = block.getSlot();
        System.out.println("  Current slot  : " + slot);
        return slot;
    }

    static void checkResult(Result<String> result, String operation) {
        if (result.isSuccessful()) {
            System.out.println("  ✓ " + operation + " tx submitted: " + result.getValue());
        } else {
            System.err.println("  ✗ " + operation + " FAILED: " + result.getResponse());
            throw new RuntimeException(operation + " transaction failed: " + result.getResponse());
        }
    }

    static void waitForTx(BackendService backendService, String txHash, String waitAddress)
            throws Exception {
        System.out.println("  Waiting for tx " + txHash + " to be confirmed...");
        int maxWait = 60;
        for (int i = 0; i < maxWait; i++) {
            Thread.sleep(5_000);
            try {
                var txResult = backendService.getTransactionService().getTransaction(txHash);
                if (txResult.isSuccessful() && txResult.getValue() != null) {
                    System.out.println("  ✓ Confirmed (attempt " + (i + 1) + ")");
                    return;
                }
            } catch (Exception e) {
                // not yet visible
            }
            System.out.print(".");
        }
        System.out.println();
        System.out.println("  WARNING: tx not confirmed after " + (maxWait * 5) + "s, continuing anyway...");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Inner types
    // ══════════════════════════════════════════════════════════════════════

    record SetNodeInfo(byte[] keyBytes, byte[] nextBytes) {}
}
