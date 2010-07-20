package org.no.ip.bca.superlearn

import java.util.UUID

import org.no.ip.bca.scala.Ranges
import org.no.ip.bca.scala.utils.actor.ReActor

object Bridger {
    import java.io._
    import java.net._
    
    def main(args: Array[String]): Unit = {
        val props = new Props(args(0))
        val melder = connect(props)
        val port = props.bridgePort
        val ssocket = new ServerSocket(port)
        handleSocket(props, melder, ssocket)
    }
    
    private def handleSocket(props: Props, melder: Melder, ssocket: ServerSocket): Unit = {
        val socket = ssocket.accept
        socket.setSoTimeout(props.timeout)
        val out = new InvokeOut(new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream))) {
            override def handleException(e: Exception) = {
                super.handleException(e)
                exit()
            }
        }
        out.start
        val clientOutbound = out.open[ClientOutbound](0)
        val serverOutbound = melder(clientOutbound)
        val in = new InvokeIn(new ObjectInputStream(new BufferedInputStream(socket.getInputStream))) {
            override def run = try {
                super.run
            } finally {
                exit()
            }
        }
        in + (0 -> serverOutbound)
        new Thread(in).start
        handleSocket(props, melder, ssocket)
    }
    
    def connect(props: Props) = {
        val dataManager = props.dataManager
        val host = props.serverHost
        val port = props.serverPort
        val socket = new Socket(host, port)
        socket.setSoTimeout(props.timeout)
        val out = new InvokeOut(new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream))) {
            override def handleException(e: Exception) = {
                super.handleException(e)
                exit()
            }
        }
        out.start
        val serverOutbound = out.open[ServerOutbound](0)
        val bridge = ActiveProxy[Bridge](new BridgeImpl(serverOutbound))
        val bridgeClientOutbound = ActiveProxy[BridgeClientOutbound](new BridgeClientOutboundImpl(dataManager))
        val in = new InvokeIn(new ObjectInputStream(new BufferedInputStream(socket.getInputStream))) {
            override def run = try {
                super.run
            } finally {
                exit()
            }
        }
        in + (0 -> bridgeClientOutbound)
        new Thread(in).start;
        new Melder(bridge, bridgeClientOutbound, serverOutbound, dataManager)
    }
}

class Melder(
        bridge: Bridge,
        bridgeClientOutbound: BridgeClientOutbound,
        serverOutbound: ServerOutbound,
        dataManager: DataManager
        ) {
    def apply(client: ClientOutbound) = {
        bridgeClientOutbound.connect(client)
        new SmartServerOutbound(new BridgeServerOutbound(bridge, serverOutbound), dataManager, client)
    }
    
    def apply() = {
        new BridgeServerOutbound(bridge, serverOutbound)
    }
    
    def connect(client: ClientOutbound) = bridgeClientOutbound.connect(client)
}

trait BridgeClientOutbound extends ClientOutbound {
    def connect(client: ClientOutbound): Unit
}

class BridgeClientOutboundImpl(dataManager: DataManager) extends BridgeClientOutbound {
    private var clients = Set.empty[ClientOutbound]
    private var matrix: Matrix = null
    private var ranges = Ranges.empty
    def connect(client: ClientOutbound): Unit = {
        clients += client
        if (matrix != null) {
            client.newWork(matrix, ranges.parts: _*)
        }
    }
    def newWork(matrix: Matrix, ranges: Ranges.Pair*): Unit = {
        this.matrix = matrix
        this.ranges = Ranges(ranges: _*)
        clients foreach { _.newWork(matrix, ranges: _*) }
    }
    def sendData(point: Long, data: Array[Byte]): Unit = {
        dataManager.write(point, data)
        clients foreach { _.sendData(point, data) }
    }
    def assigned(id: UUID, range: Ranges.Pair): Unit = {
        ranges /= range
        clients foreach { _.assigned(id, range) }
    }
}

class SmartServerOutbound(parent: ServerOutbound, dataManager: DataManager, client: ClientOutbound) extends ServerOutbound {
    def requestPoint(point: Long): Unit = {
        if (dataManager.has(point)) {
            client.sendData(point, dataManager.read(point))
        } else {
            parent.requestPoint(point)
        }
    }
    def sendMatrix(matrix: Matrix, count: Long) = parent.sendMatrix(matrix, count)
    def request(id: UUID, range: Ranges.Pair) = parent.request(id, range)
}

class BridgeServerOutbound(bridge: Bridge, parent: ServerOutbound) extends ServerOutbound {
    bridge.connect(this)
    
    def requestPoint(point: Long): Unit = parent.requestPoint(point)
    def sendMatrix(matrix: Matrix, count: Long) = bridge.sendMatrix(matrix, count, this)
    def request(id: UUID, range: Ranges.Pair) = parent.request(id, range)
}

trait Bridge {
    def connect(server: ServerOutbound): Unit
    def sendMatrix(matrix: Matrix, count: Long, server: ServerOutbound): Unit
}

class BridgeImpl(parent: ServerOutbound) extends Bridge {
    private var servers = Set.empty[ServerOutbound]
    private var checkedIn = 0
    private var nextMatrix: Matrix = null
    private var count: Long = 0
    
    def connect(server: ServerOutbound) = {
        servers += server
    }
    def sendMatrix(matrix: Matrix, count: Long, server: ServerOutbound) = {
        checkedIn += 1
        
        if (count > 0) {
            if (nextMatrix == null) {
                nextMatrix = matrix
            } else {
                UtilMethods.sum(matrix.m, nextMatrix.m)
            }
            this.count += count
        }
        
        if (servers.size == checkedIn) {
            parent.sendMatrix(nextMatrix, this.count)
            nextMatrix = null
            this.count = 0
            checkedIn = 0
        }
    }
}