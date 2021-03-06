package com.actiontech.dble.rwsplit;

import com.actiontech.dble.backend.datasource.PhysicalDbGroup;
import com.actiontech.dble.backend.datasource.PhysicalDbInstance;
import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.net.Session;
import com.actiontech.dble.net.connection.BackendConnection;
import com.actiontech.dble.plan.common.exception.MySQLOutPutException;
import com.actiontech.dble.net.connection.FrontendConnection;
import com.actiontech.dble.services.rwsplit.Callback;
import com.actiontech.dble.services.rwsplit.RWSplitHandler;
import com.actiontech.dble.services.rwsplit.RWSplitService;
import com.actiontech.dble.singleton.RouteService;
import com.actiontech.dble.util.StringUtil;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlPrepareStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MysqlDeallocatePrepareStatement;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.util.HashSet;
import java.util.Set;

public class RWSplitNonBlockingSession extends Session {

    public static final Logger LOGGER = LoggerFactory.getLogger(RWSplitNonBlockingSession.class);

    private volatile BackendConnection conn;
    private final RWSplitService rwSplitService;
    private PhysicalDbGroup rwGroup;
    private Set<String> nameSet = new HashSet<>();

    public RWSplitNonBlockingSession(RWSplitService service) {
        this.rwSplitService = service;
    }

    @Override
    public FrontendConnection getSource() {
        return (FrontendConnection) rwSplitService.getConnection();
    }

    public void execute(Boolean master, Callback callback) {
        execute(master, null, callback);
    }

    public void execute(Boolean master, Callback callback, String sql) {
        try {
            SQLStatement statement = parseSQL(sql);
            if (statement instanceof MySqlPrepareStatement) {
                String simpleName = ((MySqlPrepareStatement) statement).getName().getSimpleName();
                nameSet.add(simpleName);
                rwSplitService.setInPrepare(true);
            }
            if (statement instanceof MysqlDeallocatePrepareStatement) {
                String simpleName = ((MysqlDeallocatePrepareStatement) statement).getStatementName().getSimpleName();
                nameSet.remove(simpleName);
                if (nameSet.isEmpty()) {
                    rwSplitService.setInPrepare(false);
                }
            }
        } catch (SQLSyntaxErrorException throwables) {
            throw new MySQLOutPutException(ErrorCode.ER_OPTIMIZER, "",
                    "You have an error in your SQL syntax; check the manual that corresponds to your MySQL server version for the right syntax to use near all");
        }
        execute(master, null, callback);
    }

    public void execute(Boolean master, byte[] originPacket, Callback callback) {
        try {
            RWSplitHandler handler = new RWSplitHandler(rwSplitService, originPacket, callback, false);
            if (conn != null && !conn.isClosed()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("select bind conn[id={}]", conn.getId());
                }
                checkDest(!conn.getInstance().isReadInstance());
                handler.execute(conn);
                return;
            }

            PhysicalDbInstance instance = rwGroup.select(canRunOnMaster(master));
            checkDest(!instance.isReadInstance());
            instance.getConnection(rwSplitService.getSchema(), handler, null, false);
        } catch (IOException e) {
            LOGGER.warn("select conn error", e);
            rwSplitService.writeErrMessage(ErrorCode.ER_UNKNOWN_ERROR, e.getMessage());
        } catch (SQLSyntaxErrorException se) {
            rwSplitService.writeErrMessage(ErrorCode.ER_UNKNOWN_ERROR, se.getMessage());
        }
    }

    private Boolean canRunOnMaster(Boolean master) {
        if (!rwSplitService.isAutocommit() || rwSplitService.isTxStart() || rwSplitService.isUsingTmpTable()) {
            return true;
        }
        return master;
    }

    private void checkDest(boolean isMaster) throws SQLSyntaxErrorException {
        String dest = rwSplitService.getExpectedDest();
        if (dest == null) {
            return;
        }
        if (dest.equalsIgnoreCase("M") && isMaster) {
            return;
        }
        if (dest.equalsIgnoreCase("S") && !isMaster) {
            return;
        }
        throw new SQLSyntaxErrorException("unexpected dble_dest_expect,real[" + (isMaster ? "M" : "S") + "],expect[" + dest + "]");
    }

    private SQLStatement parseSQL(String stmt) throws SQLSyntaxErrorException {
        SQLStatementParser parser = new MySqlStatementParser(stmt);
        try {
            return parser.parseStatement();
        } catch (Exception t) {
            throw new SQLSyntaxErrorException(t);
        }
    }

    public PhysicalDbGroup getRwGroup() {
        return rwGroup;
    }

    public void executeHint(int sqlType, String sql, Callback callback) throws IOException {
        RWSplitHandler handler = new RWSplitHandler(rwSplitService, null, callback, true);
        if (conn != null && !conn.isClosed()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("select bind conn[id={}]", conn.getId());
            }
            handler.execute(conn);
            return;
        }
        try {
            PhysicalDbInstance dbInstance = RouteService.getInstance().routeRwSplit(sqlType, sql, rwSplitService);
            if (dbInstance == null) {
                return;
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("route sql {} to {}", sql, dbInstance);
            }
            dbInstance.getConnection(rwSplitService.getSchema(), handler, null, false);
        } catch (Exception e) {
            executeException(e, sql);
            return;
        }
    }

    private void executeException(Exception e, String sql) {
        sql = sql.length() > 1024 ? sql.substring(0, 1024) + "..." : sql;
        if (e instanceof SQLException) {
            SQLException sqlException = (SQLException) e;
            String msg = sqlException.getMessage();
            StringBuilder s = new StringBuilder();
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(s.append(this).append(sql).toString() + " err:" + msg);
            }
            int vendorCode = sqlException.getErrorCode() == 0 ? ErrorCode.ER_PARSE_ERROR : sqlException.getErrorCode();
            String sqlState = StringUtil.isEmpty(sqlException.getSQLState()) ? "HY000" : sqlException.getSQLState();
            String errorMsg = msg == null ? sqlException.getClass().getSimpleName() : msg;
            rwSplitService.writeErrMessage(sqlState, errorMsg, vendorCode);
        } else {
            StringBuilder s = new StringBuilder();
            LOGGER.info(s.append(this).append(sql).toString() + " err:" + e.toString(), e);
            String msg = e.getMessage();
            rwSplitService.writeErrMessage(ErrorCode.ER_PARSE_ERROR, msg == null ? e.getClass().getSimpleName() : msg);
        }
    }

    public void setRwGroup(PhysicalDbGroup rwGroup) {
        this.rwGroup = rwGroup;
    }

    public void bind(BackendConnection bindConn) {
        if (conn != null && conn != bindConn) {
            LOGGER.warn("last conn is remaining");
        }
        this.conn = bindConn;
    }

    public void unbindIfSafe() {
        if (rwSplitService.isAutocommit() && !rwSplitService.isTxStart() && !rwSplitService.isLocked() &&
                !rwSplitService.isInLoadData() &&
                !rwSplitService.isInPrepare() && this.conn != null && !rwSplitService.isUsingTmpTable()) {
            this.conn.release();
            this.conn = null;
        }
    }

    public void unbind() {
        this.conn = null;
    }

    public void close(String reason) {
        if (conn != null) {
            conn.close(reason);
        }
    }

    public boolean closed() {
        return rwSplitService.getConnection().isClosed();
    }

    public RWSplitService getService() {
        return rwSplitService;
    }
}
