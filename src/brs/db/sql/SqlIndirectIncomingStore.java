package brs.db.sql;

import brs.IndirectIncoming;
import brs.db.BurstKey;
import brs.db.store.DerivedTableManager;
import brs.db.store.IndirectIncomingStore;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import static brs.schema.Tables.INDIRECT_INCOMING;

public class SqlIndirectIncomingStore implements IndirectIncomingStore {

    private final EntitySqlTable<IndirectIncoming> indirectIncomingTable;
    private final BurstKey.LinkKeyFactory<IndirectIncoming> indirectIncomingDbKeyFactory;

    public SqlIndirectIncomingStore(DerivedTableManager derivedTableManager) {
        indirectIncomingDbKeyFactory = new DbKey.LinkKeyFactory<IndirectIncoming>("account_id", "transaction_id") {
            @Override
            public BurstKey newKey(IndirectIncoming indirectIncoming) {
                return newKey(indirectIncoming.getAccountId(), indirectIncoming.getTransactionId());
            }
        };

        this.indirectIncomingTable = new EntitySqlTable<IndirectIncoming>("indirect_incoming", INDIRECT_INCOMING, indirectIncomingDbKeyFactory, derivedTableManager) {
            @Override
            protected IndirectIncoming load(DSLContext ctx, Record rs) {
                return new IndirectIncoming(rs.get(INDIRECT_INCOMING.ACCOUNT_ID),
                    rs.get(INDIRECT_INCOMING.TRANSACTION_ID),
                    rs.get(INDIRECT_INCOMING.AMOUNT),
                    rs.get(INDIRECT_INCOMING.QUANTITY),
                    rs.get(INDIRECT_INCOMING.HEIGHT));
            }

            private Query getQuery(DSLContext ctx, IndirectIncoming indirectIncoming) {
                return ctx.insertInto(INDIRECT_INCOMING, INDIRECT_INCOMING.ACCOUNT_ID, INDIRECT_INCOMING.TRANSACTION_ID,
                    INDIRECT_INCOMING.AMOUNT, INDIRECT_INCOMING.QUANTITY,
                    INDIRECT_INCOMING.HEIGHT)
                        .values(indirectIncoming.getAccountId(), indirectIncoming.getTransactionId(),
                            indirectIncoming.getAmount(), indirectIncoming.getQuantity(),
                            indirectIncoming.getHeight());
            }

            @Override
            void save(DSLContext ctx, IndirectIncoming indirectIncoming) {
                getQuery(ctx, indirectIncoming).execute();
            }

            @Override
            void save(DSLContext ctx, Collection<IndirectIncoming> indirectIncomings) {
              Iterator<IndirectIncoming> iterator = indirectIncomings.iterator();
              while(iterator.hasNext()) {
                // break into batches of 50k queries max
                List<Query> queries = new ArrayList<>();
                for (int i = 0; i < 50000 && iterator.hasNext(); i++) {
                  queries.add(getQuery(ctx, iterator.next()));                  
                }
                try {
                    ctx.batch(queries).execute();
                }
                catch (DataAccessException e) {
                    // TODO: remove this catch after better handling of indirects and forks
                }
              }
            }
        };
    }

    @Override
    public void addIndirectIncomings(Collection<IndirectIncoming> indirectIncomings) {
        Db.useDSLContext(ctx -> {
            indirectIncomingTable.save(ctx, indirectIncomings);
        });
    }

    @Override
    public Collection<Long> getIndirectIncomings(long accountId, int from, int to) {
        return indirectIncomingTable.getManyBy(INDIRECT_INCOMING.ACCOUNT_ID.eq(accountId), from, to)
                .stream()
                .map(IndirectIncoming::getTransactionId)
                .collect(Collectors.toList());
    }
    
    @Override
    public IndirectIncoming getIndirectIncoming(long accountId, long transactionId) {
        return indirectIncomingTable.get(indirectIncomingDbKeyFactory.newKey(accountId, transactionId));
    }

    @Override
    public void rollback(int height) {
        indirectIncomingTable.rollback(height);
    }
}
