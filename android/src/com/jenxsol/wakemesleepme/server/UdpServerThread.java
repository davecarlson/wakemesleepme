package com.jenxsol.wakemesleepme.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.LinkedBlockingQueue;

import android.os.Handler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jenxsol.wakemesleepme.consts.Iface;
import com.jenxsol.wakemesleepme.consts.Packets;
import com.jenxsol.wakemesleepme.models.WrappedPacket;
import com.jenxsol.wakemesleepme.server.UdpServer.EventPacketReceived;
import com.jenxsol.wakemesleepme.server.UdpServer.EventServerStarted;
import com.jenxsol.wakemesleepme.server.UdpServer.EventServerStopped;
import com.jenxsol.wakemesleepme.ui.fragments.LoggingFragment.EventLogOutput;
import com.jenxsol.wakemesleepme.utils.QLog;
import com.jenxsol.wakemesleepme.utils.WiFiSupport;

import de.greenrobot.event.EventBus;

class UdpServerThread extends Thread implements Iface, Packets, OnDataReceivedListener
{

    private static final EventBus sBus = EventBus.getDefault();

    // Send queue
    private final LinkedBlockingQueue<DatagramPacket> mPacketSendQueue = new LinkedBlockingQueue<DatagramPacket>();

    private boolean mIsStarted = false;
    private boolean mIsFinished = false;
    private boolean mIsRunning = false;
    private boolean run = true;

    private InetAddress mBroadcastAddress = null;
    private DatagramSocket mSocket;
    private UdpListenThread mListenerThread;

    public UdpServerThread(Handler handler)
    {
    }

    /**
     * True on success
     * 
     * @return
     */
    private boolean init()
    {
        if (!WiFiSupport.isWiFiConnected())
        {
            QLog.d("WiFi Off");
            sBus.post(new EventLogOutput("WiFi Off, Do nothing. sorry!"));
            // TODO send sticky event
            return false;
        }
        try
        {
            mBroadcastAddress = WiFiSupport.getBroadcastAddress();
            // QLog.d("WiFi Broadcast address: " + mBroadcastAddress.getHostAddress());
            mSocket = new DatagramSocket(UDP_PORT_ANDROID);
            mSocket.setBroadcast(true);
            mListenerThread = new UdpListenThread(mSocket, this);
            sBus.post(new EventLogOutput("UDP Server bound to: "
                    + mSocket.getLocalAddress().getHostAddress() + ":" + UDP_PORT_ANDROID));
            return true;
        }
        catch (SocketException e)
        {
            e.printStackTrace();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public void run()
    {
        mIsStarted = true;
        if (!init())
        {
            finished();
            return;
        }
        started();
        while (run)
        {
            mIsRunning = true;
            run = work();
        }
        finished();
    }

    private static final Gson mGson = new GsonBuilder().create();

    /**
     * @see com.jenxsol.wakemesleepme.server.OnDataReceivedListener#onDataReceived(java.net.DatagramPacket)
     */
    @Override
    public void onDataReceived(DatagramPacket packet)
    {
        sBus.post(new EventLogOutput("Received Packet: " + packet.getAddress().getHostAddress()
                + " Message: " + new String(packet.getData())));

        WrappedPacket wp = new WrappedPacket();
        wp.setIpAddress(packet.getAddress().getHostAddress());
        wp.setTime(System.currentTimeMillis());
        wp.setPacket(packet);
        sBus.post(new EventPacketReceived(wp));
    }

    /**
     * Does actual work or waits in here
     * 
     * @return false if we finished do work
     */
    protected boolean work()
    {
        // Check state before doing work
        if (mSocket == null)
            return false;
        if (!mSocket.isBound())
            return false;
        if (mSocket.isClosed())
            return false;

        try
        {
            return sendPacket(mPacketSendQueue.take());
        }
        catch (InterruptedException e)
        {
            return false;
        }
    }

    /**
     * Add a packet to the send queue. This will add the address and port to the packet
     * 
     * @param data
     * @param length
     * @return true if added to the queue.. does not mean the packet has been sent tho!
     */
    boolean addPacketToSend(byte[] data, int length)
    {
        if (data == null || length == 0)
            return false;
        return mPacketSendQueue.add(createDatagramPacket(data, length));
    }

    /**
     * Add a datagram packet to be server, pre-formatted
     * 
     * @param packet
     * @return
     */
    boolean addPacketToSend(DatagramPacket packet)
    {
        if (packet == null)
            return false;
        return mPacketSendQueue.add(createDatagramPacket(packet));
    }

    /**
     * Stop the server don't use {@link #stop()} doesn't work!
     */
    void stopServer()
    {
        run = false;
        mPacketSendQueue.clear();
        interrupt();
    }

    boolean isStarted()
    {
        return mIsStarted;
    }

    boolean isRunning()
    {
        return mIsRunning;
    }

    boolean isFinished()
    {
        return mIsFinished;
    }

    /**
     * Creates a broadcast packet for this device
     * 
     * @param data
     * @param length
     * @return
     */
    private DatagramPacket createDatagramPacket(byte[] data, int length)
    {
        if (mBroadcastAddress == null)
            return null;
        final DatagramPacket p = new DatagramPacket(data, length, mBroadcastAddress,
                UDP_PORT_DESKTOP);
        return p;
    }

    /**
     * Makes sure you packet has an address and port set, defaults to udp desktop port and broadcast
     * address
     * 
     * @param p
     * @return populated packet
     */
    private DatagramPacket createDatagramPacket(DatagramPacket p)
    {
        if (p == null)
            return p;
        if (p.getAddress() == null)
            p.setAddress(mBroadcastAddress);
        if (p.getPort() == 0)
            p.setPort(UDP_PORT_DESKTOP);
        return p;
    }

    /**
     * Internal method to send packets
     * 
     * @param packet
     * @return
     */
    private boolean sendPacket(final DatagramPacket packet)
    {
        if (mSocket == null)
            return false;
        try
        {
            mSocket.send(packet);

            sBus.post(new EventLogOutput("UDP Packet \" " + new String(packet.getData())
                    + " \" sent to: " + mBroadcastAddress.getHostAddress() + ":"
                    + packet.getPort()));
            QLog.d("Packet: " + packet + " Sent.");
            return true;
        }
        catch (IOException e)
        {
            QLog.w("Error Sending Packet", e);
            stopServer();
        }
        return false;
    }

    /**
     * Interal state and event saying the server is running.
     */
    private void started()
    {
        sBus.postSticky(new EventServerStarted());
    }

    private void shutdown()
    {
        if (mSocket != null)
        {

            mSocket.close();
            mListenerThread.run = false;
            mListenerThread.interrupt();
            mListenerThread = null;
            sBus.post(new EventLogOutput("UDP Server closed"));
            QLog.d("UDP Socket Closed");
        }
    }

    /**
     * Set the thread state to finished
     */
    private void finished()
    {
        shutdown();
        mIsRunning = false;
        mIsFinished = true;
        sBus.removeStickyEvent(EventServerStarted.class);
        sBus.post(new EventServerStopped());
    }

    static final class UdpListenThread extends Thread
    {

        private OnDataReceivedListener mListener;
        private DatagramSocket mSocket;
        private boolean run = false;

        private UdpListenThread(final DatagramSocket socket, final OnDataReceivedListener listener)
        {
            mSocket = socket;
            mListener = listener;
            if (mSocket == null || mSocket.isClosed())
            {

                return;
            }
            run = true;
            sBus.post(new EventLogOutput("Start listening"));
            start();
        }

        /**
         * @see java.lang.Thread#run()
         */
        @Override
        public void run()
        {
            while (run)
            {
                run = listen();
            }
        }

        private boolean listen()
        {
            // Empty data
            final byte[] message = new byte[1500];
            final DatagramPacket emptyPacket = new DatagramPacket(message, message.length);
            try
            {
                mSocket.receive(emptyPacket);
                mListener.onDataReceived(emptyPacket);
                return true;
            }
            catch (IOException e)
            {
                QLog.w("UDP Receive error", e);
                return false;
            }
        }
    }

}