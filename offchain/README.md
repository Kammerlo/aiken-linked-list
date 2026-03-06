# Off-chain Demo

A [JBang](https://www.jbang.dev/) Java script that drives the Aiken Linked List validator through a complete lifecycle on a local Cardano devnet.

## What it does

`LinkedListDemo.java` executes the following sequence against the on-chain validator:

1. **Init** – deploys the head node, minting the `FSN` origin token and locking it at the script address.
2. **Insert (user1 & user2)** – inserts two nodes into the list, each minting a `FSN<pkh>` token.
3. **Remove (user1)** – removes user1's node, burning its token and returning the ADA.

Each step is idempotent: if the chain state shows the step is already done it is skipped.

## Prerequisites

| Requirement | Details |
|---|---|
| Java 21 | Required by JBang |
| [JBang](https://www.jbang.dev/) | Handles dependency resolution; no Maven/Gradle needed |
| Local devnet | Run `docker compose up` to start **yaci-devkit** (Blockfrost-compatible API on `localhost:8080`) |
| Funded sender | Set `SENDER_MNEMONIC` in the script to a mnemonic funded on the devnet |

## Running

```bash
# Start the local devnet
docker compose up -d

# Run the demo (from the offchain/ directory)
jbang LinkedListDemo.java
```

## Configuration

All tuneable values are constants at the top of `LinkedListDemo.java`:

| Constant | Default | Description |
|---|---|---|
| `BLOCKFROST_URL` | `http://localhost:8080/api/v1/` | Devnet API endpoint |
| `SENDER_MNEMONIC` | test mnemonic | Admin account paying fees |
| `DEADLINE_POSIX_MS` | year 2099 | Validity deadline for Insert/Remove |
| `NODE_MIN_LOVELACE` | 2 000 000 | Minimum ADA locked with each node UTxO |

## Dependencies

- [`cardano-client-lib`](https://github.com/bloxbean/cardano-client-lib) `0.7.1` — transaction building
- [`aiken-java-binding`](https://github.com/bloxbean/aiken-java-binding) `0.1.0` — script parameter application
- `Blockfrost` backend via [yaci-devkit](https://github.com/bloxbean/yaci-devkit)
