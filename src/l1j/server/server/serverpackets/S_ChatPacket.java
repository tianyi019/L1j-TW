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
package l1j.server.server.serverpackets;

import l1j.server.server.model.Instance.L1PcInstance;

// Referenced classes of package l1j.server.server.serverpackets:
// ServerBasePacket

public class S_ChatPacket extends ServerBasePacket
{
	public S_ChatPacket(L1PcInstance pc, String chat, int opcode, int type)
	{
		if (type == 0) { // 通常チャット
			writeC(opcode);
			writeC(type);
			writeD(pc.getId());
			writeS(pc.getName() + ": " + chat);
		} else if (type == 2) { // 叫び
			writeC(opcode);
			writeC(type);
			if (pc.isInvisble()) {
				writeD(0);
			} else {
				writeD(pc.getId());
			}
			writeS("<" + pc.getName() + "> " + chat);
			writeH(pc.getX());
			writeH(pc.getY());
		} else if (type == 3) { // 全体チャット
			writeC(opcode);
			writeC(type);
			if (pc.isGm() == true && pc.getAccessLevel() == 200) {
				writeS("[******] " + "\\fW" + chat);
			} else if (pc.isGm() == true && pc.getAccessLevel() == 150) {
				writeS("[******] " + "\\fT" + chat);
			} else if (pc.isGm() == true && pc.getAccessLevel() == 100) {
				writeS("[******] " + chat);
			} else {
				writeS("[" + pc.getName() + "] " + chat);
			}
		} else if (type == 4) { // 血盟チャット
			writeC(opcode);
			writeC(type);
			writeS("{" + pc.getName() + "} " + chat);
		} else if (type == 9) { // ウィスパー
			writeC(opcode);
			writeC(type);
			writeS("-> (" + pc.getName() + ") " + chat);
		} else if (type == 11) { // パーティーチャット
			writeC(opcode);
			writeC(type);
			writeS("(" + pc.getName() + ") " + chat);
		} else if (type == 12) { // トレードチャット
			writeC(opcode);
			writeC(type);
			writeS("[" + pc.getName() + "] " + chat);
		} else if (type == 13) { // 連合チャット
			writeC(opcode);
			writeC(type);
			writeS("{{" + pc.getName() + "}} " + chat);
		} else if (type == 14) { // チャットパーティー
			writeC(opcode);
			writeC(type);
			if (pc.isInvisble()) {
				writeD(0);
			} else {
				writeD(pc.getId());
			}
			writeS("(" + pc.getName() + ") " + chat);
		} else if (type == 16) { // ウィスパー
			writeC(opcode);
			writeS(pc.getName());
			writeS(chat);
		}
	}

	@Override
	public byte[] getContent()
	{
		return getBytes();
	}
}