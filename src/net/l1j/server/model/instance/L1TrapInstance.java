/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA
 * 02111-1307, USA.
 *
 * http://www.gnu.org/copyleft/gpl.html
 */
package net.l1j.server.model.instance;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.l1j.server.model.L1Location;
import net.l1j.server.model.L1Object;
import net.l1j.server.model.map.L1Map;
import net.l1j.server.model.trap.L1Trap;
import net.l1j.server.serverpackets.S_RemoveObject;
import net.l1j.server.serverpackets.S_Trap;
import net.l1j.server.types.Point;
import net.l1j.util.RandomArrayList;

import static net.l1j.server.model.skill.SkillId.*;

public class L1TrapInstance extends L1Object {
	private static final long serialVersionUID = 1L;

	private final L1Trap _trap;
	private final Point _baseLoc = new Point();
	private final Point _rndPt = new Point();
	private final int _span;
	private boolean _isEnable = true;
	private final String _nameForView;

	private List<L1PcInstance> _knownPlayers = new CopyOnWriteArrayList<L1PcInstance>();

	public L1TrapInstance(int id, L1Trap trap, L1Location loc, Point rndPt, int span) {
		setId(id);
		_trap = trap;
		getLocation().set(loc);
		_baseLoc.set(loc);
		_rndPt.set(rndPt);
		_span = span;
		_nameForView = "trap";

		resetLocation();
	}

	public L1TrapInstance(int id, L1Location loc) {
		setId(id);
		_trap = L1Trap.newNull();
		getLocation().set(loc);
		_span = 0;
		_nameForView = "trap base";
	}

	private final byte[] WorR_Way = { -1, 1 }; // 用來決定方向權的矩陣

	public void resetLocation() {
		if (_rndPt.getX() == 0 && _rndPt.getY() == 0) {
			return;
		}

		for (int i = 0; i < 50; i++) {
			int rndX = RandomArrayList.getInt(_rndPt.getX()) * WorR_Way[RandomArrayList.getInt(2)]; // 1/2の確率でマイナスにする
			int rndY = RandomArrayList.getInt(_rndPt.getY()) * WorR_Way[RandomArrayList.getInt(2)];

			rndX += _baseLoc.getX();
			rndY += _baseLoc.getY();

			L1Map map = getLocation().getMap();
			if (map.isInMap(rndX, rndY) && map.isPassable(rndX, rndY)) {
				getLocation().set(rndX, rndY);
				break;
			}
		}
		// ループ內で位置が確定しない場合、前回と同じ位置になる。
	}

	public void enableTrap() {
		_isEnable = true;
	}

	public void disableTrap() {
		_isEnable = false;

		for (L1PcInstance pc : _knownPlayers) {
			pc.removeKnownObject(this);
			pc.sendPackets(new S_RemoveObject(this));
		}
		_knownPlayers.clear();
	}

	public boolean isEnable() {
		return _isEnable;
	}

	public int getSpan() {
		return _span;
	}

	public void onTrod(L1PcInstance trodFrom) {
		_trap.onTrod(trodFrom, this);
	}

	public void onDetection(L1PcInstance caster) {
		_trap.onDetection(caster, this);
	}

	@Override
	public void onPerceive(L1PcInstance perceivedFrom) {
		if (perceivedFrom.hasSkillEffect(GMSTATUS_SHOWTRAPS)) {
			perceivedFrom.addKnownObject(this);
			perceivedFrom.sendPackets(new S_Trap(this, _nameForView));
			_knownPlayers.add(perceivedFrom);
		}
	}
}
