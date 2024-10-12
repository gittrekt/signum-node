package brs;

import brs.fluxcapacitor.FluxValues;
import brs.services.BlockService;
import brs.services.TransactionService;
import brs.util.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.security.MessageDigest;
import java.math.BigInteger;

/**
 * Economic Clustering concept (EC) solves the most critical flaw of "classical" Proof-of-Stake - the problem called
 * "Nothing-at-Stake".
 *
 * I ought to respect BCNext's wish and say that this concept is inspired by Economic Majority idea of Meni Rosenfeld
 * (http://en.wikipedia.org/wiki/User:Meni_Rosenfeld).
 *
 * EC is a vital part of Transparent Forging. Words "Mining in Nxt relies on cooperation of people and even forces it"
 * (https://bitcointalk.org/index.php?topic=553205.0) were said about EC.
 *
 * Keep in mind that this concept has not been peer reviewed. You are very welcome to do it...
 *
 *                                                                              Come-from-Beyond (21.05.2014)
 */
public final class EconomicClustering {

  private static final Logger logger = LoggerFactory.getLogger(EconomicClustering.class);

  private final Blockchain blockchain;
  private final BlockService blockService;
  private final TransactionService transactionService;

  public EconomicClustering(Blockchain blockchain, BlockService blockService, TransactionService transactionService) {
    this.blockchain = blockchain;
    this.blockService = blockService;
    this.transactionService = transactionService;
  }

  public Block getECBlock(int timestamp) {
    Block block = blockchain.getLastBlock();
    if (timestamp < block.getTimestamp() - 15) {
      throw new IllegalArgumentException("Timestamp cannot be more than 15 s earlier than last block timestamp: " + block.getTimestamp());
    }
    int distance = 0;
    while (block.getTimestamp() > timestamp - Constants.EC_RULE_TERMINATOR && distance < Constants.EC_BLOCK_DISTANCE_LIMIT) {
      block = blockchain.getBlock(block.getPreviousBlockId());
      distance += 1;
    }
    return block;
  }

  public boolean verifyFork(Transaction transaction) {
    try {
        if (transaction == null || blockchain == null) {
            logger.warn("Invalid transaction or blockchain state");
            return false;
        }

        if (!Signum.getFluxCapacitor().getValue(FluxValues.DIGITAL_GOODS_STORE)) {
            return true;
        }

        // EC check is now performed regardless of the full hash reference
        int currentHeight = blockchain.getHeight();
        int transactionEcBlockHeight = transaction.getEcBlockHeight();

        // Simplified EC block distance check
        if (currentHeight < Constants.EC_CHANGE_BLOCK_1 && 
            currentHeight - transactionEcBlockHeight > Constants.EC_BLOCK_DISTANCE_LIMIT) {
            logger.debug("Transaction EC block too old: {} (current height: {})", transactionEcBlockHeight, currentHeight);
            return false;
        }

        Block ecBlock = blockchain.getBlock(transaction.getEcBlockId());
        if (ecBlock == null) {
            logger.debug("EC block not found: {}", transaction.getEcBlockId());
            return false;
        }

        if (!constantTimeEquals(ecBlock.getHeight(), transactionEcBlockHeight)) {
            logger.debug("EC block height mismatch: {} != {}", ecBlock.getHeight(), transactionEcBlockHeight);
            return false;
        }

        // Verify the chain of blocks
        Block currentBlock = ecBlock;
        for (int i = 0; i < Constants.EC_VERIFICATION_DEPTH; i++) {
            if (currentBlock == null) {
                return false;
            }

            // Verify block signature
            if (!blockService.verifyBlockSignature(currentBlock)) {
                logger.debug("Block signature verification failed for block {}", currentBlock.getStringId());
                return false;
            }

            // Check cumulative difficulty
            Block previousBlock = blockchain.getBlock(currentBlock.getPreviousBlockId());
            if (previousBlock == null) {
                logger.debug("Previous block not found for block {}", currentBlock.getStringId());
                return false;
            }
            if (currentBlock.getCumulativeDifficulty().compareTo(previousBlock.getCumulativeDifficulty()) <= 0) {
                logger.debug("Cumulative difficulty not increasing for block {}", currentBlock.getStringId());
                return false;
            }

            // Validate transactions in the block
            for (Transaction tx : currentBlock.getTransactions()) {
                if (!transactionService.verifyPublicKey(tx)) {
                    logger.debug("Transaction {} failed public key verification in block {}", 
                                 tx.getStringId(), currentBlock.getStringId());
                    return false;
                }
            }

            // Check economic clustering rules
            if (currentHeight < Constants.EC_CHANGE_BLOCK_1 && 
                currentHeight - currentBlock.getHeight() > Constants.EC_BLOCK_DISTANCE_LIMIT) {
                logger.debug("Block {} is too old for economic clustering rules", currentBlock.getStringId());
                return false;
            }

            // Check block timestamp
            Block lastBlock = blockchain.getLastBlock();
            int timeDifference = currentBlock.getTimestamp() - lastBlock.getTimestamp();
            if (timeDifference > Constants.MAX_TIMESTAMP_DIFFERENCE || timeDifference <= 0) {
                logger.debug("Block {} timestamp is out of bounds", currentBlock.getStringId());
                return false;
            }

            currentBlock = previousBlock;
        }

        return true;
    } catch (Exception e) {
        logger.debug("Error during fork verification with transaction: {}", JSON.toJsonString(transaction.getJsonObject()));
        return false;
    }
  }

  private boolean constantTimeEquals(int a, int b) {
    byte[] aBytes = BigInteger.valueOf(a).toByteArray();
    byte[] bBytes = BigInteger.valueOf(b).toByteArray();

    // Ensure both arrays have the same length by padding with leading zeros
    int maxLength = Math.max(aBytes.length, bBytes.length);
    byte[] aPadded = new byte[maxLength];
    byte[] bPadded = new byte[maxLength];

    System.arraycopy(aBytes, 0, aPadded, maxLength - aBytes.length, aBytes.length);
    System.arraycopy(bBytes, 0, bPadded, maxLength - bBytes.length, bBytes.length);

    return MessageDigest.isEqual(aPadded, bPadded);
  }
}