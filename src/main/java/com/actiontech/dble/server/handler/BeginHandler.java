/*
 * Copyright (C) 2016-2021 ActionTech.
 * based on code by MyCATCopyrightHolder Copyright (c) 2013, OpenCloudDB/MyCAT.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */
package com.actiontech.dble.server.handler;

import com.actiontech.dble.log.transaction.TxnLogHelper;
import com.actiontech.dble.services.mysqlsharding.ShardingService;
import com.actiontech.dble.statistic.sql.StatisticListener;

import java.util.Optional;

public final class BeginHandler {
    private BeginHandler() {
    }

    public static void handle(String stmt, ShardingService service) {
        if (service.isTxStart() || !service.isAutocommit()) {
            service.beginInTx(stmt);
        } else {
            service.setTxStart(true);
            Optional.ofNullable(StatisticListener.getInstance().getRecorder(service)).ifPresent(r -> r.onTxStartByBegin(service));
            TxnLogHelper.putTxnLog(service, stmt);
            service.writeOkPacket();
        }
    }
}
