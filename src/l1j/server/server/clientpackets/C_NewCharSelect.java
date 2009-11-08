/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA
 * 02111-1307, USA.
 *
 * http://www.gnu.org/copyleft/gpl.html
 */
package l1j.server.server.clientpackets;

import java.util.logging.Logger;

import l1j.server.server.Account;
import l1j.server.server.ClientThread;
import l1j.server.server.model.L1World;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.serverpackets.S_PacketBox;

public class C_NewCharSelect extends ClientBasePacket
{
	private Logger _log = Logger.getLogger(C_NewCharSelect.class.getName());
	public C_NewCharSelect(byte[] decrypt, ClientThread client)
	{
		super(decrypt);

		client.CharReStart(true);

		if (client.getActiveChar() != null)
		{
			L1PcInstance pc = client.getActiveChar();
			_log.fine("Disconnect from: " + pc.getName());
			//XXX 修正死亡洗血bug
			if (pc.isDead()) {
				return;
			}
			ClientThread.quitGame(pc);

			synchronized (pc)
			{
				pc.logout();
				client.setActiveChar(null);
			}

			client.sendPacket(new S_PacketBox(S_PacketBox.LOGOUT));
		}
		else
			_log.fine("Disconnect Request from Account : " + client.getAccountName());
	}
}