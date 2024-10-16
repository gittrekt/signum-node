package brs;

import brs.at.AtConstants;
import brs.fluxcapacitor.FluxValues;
import brs.services.BlockService;
import brs.services.TransactionService;
import brs.util.JSON;

import java.math.BigInteger;
import java.util.Set;
import java.util.HashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    if (timestamp < block.getTimestamp() - Constants.MAX_TIMESTAMP_DIFFERENCE) {
      String errorMessage = "Timestamp: " + timestamp + " cannot be more than 15 s earlier than last block timestamp: " + block.getTimestamp();
      throw new IllegalArgumentException(errorMessage);
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
            logger.error("Invalid transaction or blockchain state");
            return false;
        }

        AtConstants atConstants = AtConstants.getInstance();
        Block block = blockchain.getLastBlock();
        Block ecBlock = blockchain.getBlockAtHeight(transaction.getEcBlockHeight());
        int currentHeight = blockchain.getHeight();
        int currentBlockTimestamp = block.getTimestamp();
        int maxTimestampDifference = (int) (atConstants.averageBlockMinutes(currentHeight) * 60); // * 0.5); // half the block time to avoid transaction manipulation?
        int transactionTimestamp = transaction.getTimestamp();
        int transactionEcBlockHeight = transaction.getEcBlockHeight();

        // Check if the transaction timestamp is within the allowed range
        if (transactionTimestamp < currentBlockTimestamp - maxTimestampDifference) {
            logger.error("Transaction timestamp too far in the past. Transaction: {}, Block: {}, Difference: {} Max Difference: {}",
                         transactionTimestamp, currentBlockTimestamp,
                         currentBlockTimestamp - transactionTimestamp, maxTimestampDifference);
            return false;
        }

        // Digital Goods Store check
        if (!Signum.getFluxCapacitor().getValue(FluxValues.DIGITAL_GOODS_STORE)) {
            return true;
        }

        if (ecBlock == null || ecBlock.getId() != transaction.getEcBlockId()) {
            logger.error("Transaction EC block is not on the main chain");
            return false;
        }

        // Check if the EC block is within a reasonable range
        if (currentHeight < Constants.EC_CHANGE_BLOCK_1 &&
            Math.abs(currentHeight - transactionEcBlockHeight) > Constants.EC_BLOCK_DISTANCE_LIMIT) {
            logger.error("Transaction EC block too far from current height: {} (current height: {})", transactionEcBlockHeight, currentHeight);
            return false;
        }

        // make sure we are on a valid path
        Block currentBlock = ecBlock;
        BigInteger cumulativeEconomicWeight = BigInteger.ZERO;
        BigInteger cumulativeDifficulty = BigInteger.ZERO;

        for (int i = 0; i < Constants.EC_VERIFICATION_DEPTH; i++) {
            logger.debug("Verifying block at depth {}: {}", i, currentBlock.getStringId());
            Block previousBlock = blockchain.getBlock(currentBlock.getPreviousBlockId());

            BigInteger mainChainDifficulty = previousBlock.getCumulativeDifficulty();
            BigInteger forkDifficulty = currentBlock.getCumulativeDifficulty();
            cumulativeDifficulty = cumulativeDifficulty.add(forkDifficulty.subtract(mainChainDifficulty));

            long economicWeight = blockService.calculateEconomicWeight(currentBlock);
            cumulativeEconomicWeight = cumulativeEconomicWeight.add(BigInteger.valueOf(economicWeight));

            // Consider both difficulty and economic weight
            BigInteger forkStrength = cumulativeDifficulty.multiply(cumulativeEconomicWeight);
            BigInteger mainChainStrength = mainChainDifficulty.multiply(BigInteger.valueOf(i + 1));

            if (forkStrength.compareTo(mainChainStrength) <= 0) {
                logger.error("Fork cumulative difficulty and economic weight are not higher than main chain");
                return false;
            }

            // Verify block signature
            if (!blockService.verifyBlockSignature(currentBlock)) {
                logger.error("Block signature verification failed for block {}", currentBlock.getStringId());
                return false;
            }

            // Validate transactions in the block
            Set<Long> transactionIds = new HashSet<>();
            for (Transaction tx : currentBlock.getTransactions()) {
                if (!transactionIds.add(tx.getId())) {
                    logger.error("Duplicate transaction found in block");
                    return false;
                }

                // Verify transaction public key
                if (!transactionService.verifyPublicKey(tx)) {
                    logger.error("Transaction {} failed public key verification in block {}",
                                 tx.getStringId(), currentBlock.getStringId());
                    return false;
                }

                // Check economic clustering rules for previous block validation
                if (currentBlock.getHeight() < Constants.EC_CHANGE_BLOCK_1 &&
                    Math.abs(currentBlock.getHeight() - tx.getEcBlockHeight()) > Constants.EC_BLOCK_DISTANCE_LIMIT) {
                    logger.error("Block {} is too old for economic clustering rules", currentBlock.getStringId());
                    return false;
                }
            }

            currentBlock = previousBlock;
        }

        return ecBlock.getHeight() == transaction.getEcBlockHeight();
    } catch (Exception e) {
        logger.error("Error during fork verification: {} Error: {}", JSON.toJsonString(transaction.getJsonObject()), e.getMessage());
        return false;
    }
  }
}
