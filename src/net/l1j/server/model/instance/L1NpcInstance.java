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
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import javolution.util.FastList;
import javolution.util.FastMap;
import javolution.util.FastTable;

import net.l1j.Config;
import net.l1j.server.ActionCodes;
import net.l1j.server.datatables.NpcChatTable;
import net.l1j.server.datatables.NpcTable;
import net.l1j.server.model.L1Attack;
import net.l1j.server.model.L1Character;
import net.l1j.server.model.L1GroundInventory;
import net.l1j.server.model.L1HateList;
import net.l1j.server.model.L1Inventory;
import net.l1j.server.model.L1Magic;
import net.l1j.server.model.L1MobGroupInfo;
import net.l1j.server.model.L1MobSkillUse;
import net.l1j.server.model.L1NpcRegenerationTimer;
import net.l1j.server.model.L1NpcChatTimer;
import net.l1j.server.model.L1Object;
import net.l1j.server.model.L1Spawn;
import net.l1j.server.model.L1World;
import net.l1j.server.model.map.L1Map;
import net.l1j.server.model.map.L1WorldMap;
import net.l1j.server.model.skill.SkillUse;
import net.l1j.server.serverpackets.S_ChangeShape;
import net.l1j.server.serverpackets.S_RemoveObject;
import net.l1j.server.serverpackets.S_DoActionGFX;
import net.l1j.server.serverpackets.S_MoveCharPacket;
import net.l1j.server.serverpackets.S_NPCPack;
import net.l1j.server.serverpackets.S_SkillHaste;
import net.l1j.server.serverpackets.S_SkillSound;
import net.l1j.server.templates.L1Npc;
import net.l1j.server.templates.L1NpcChat;
import net.l1j.server.types.Base;
import net.l1j.server.types.Point;
import net.l1j.thread.ThreadPoolManager;
import net.l1j.util.MoveUtil;
import net.l1j.util.RandomArrayList;
import net.l1j.util.TimerPool;

import static net.l1j.server.model.item.ItemId.*;
import static net.l1j.server.model.skill.SkillId.*;

public class L1NpcInstance extends L1Character {
	private static final long serialVersionUID = 1L;

	public static final int MOVE_SPEED = 0;
	public static final int ATTACK_SPEED = 1;
	public static final int MAGIC_SPEED = 2;

	public static final int HIDDEN_STATUS_NONE = 0;
	public static final int HIDDEN_STATUS_SINK = 1;
	public static final int HIDDEN_STATUS_FLY = 2;
	public static final int HIDDEN_STATUS_ICE = 3;
	public static final int HIDDEN_STATUS_COUNTER_BARRIER = 4; // 吉爾塔斯反擊屏障

	public static final int CHAT_TIMING_APPEARANCE = 0;
	public static final int CHAT_TIMING_DEAD = 1;
	public static final int CHAT_TIMING_HIDE = 2;
	public static final int CHAT_TIMING_GAME_TIME = 3;

	private final static Logger _log = Logger.getLogger(L1NpcInstance.class.getName());

	private L1Npc _npcTemplate;
	private L1Spawn _spawn;
	private int _spawnNumber; // L1Spawnで管理されているナンバー

	private int _petcost; // ペットになったときのコスト
	public L1Inventory _inventory = new L1Inventory();
	private L1MobSkillUse mobSkill;

	// 對象を初めて發見したとき。（テレポート用）
	private boolean firstFound = true;

	// 經路探索範圍（半徑） ※上げすぎ注意！！
	public static int courceRange = 18;

	// 吸われたMP
	private int _drainedMana = 0;

	// 休憩
	private boolean _rest = false;

	// 設定休息次數，預設0才能讓第一次看到玩家的怪是走動的而非休息的。
	private int sleeptime_PT = 0;
	// ランダム移動時の距離と方向
	private int _randomMoveDistance = 0;

	private int _randomMoveDirection = 0;

	// ■■■■■■■■■■■■■ ＡＩ關連 ■■■■■■■■■■■

	interface NpcAI {
		public void start();
	}

	protected void startAI() {
		if (Config.NPCAI_IMPLTYPE == 1) {
			new NpcAITimerImpl().start();
		} else if (Config.NPCAI_IMPLTYPE == 2) {
			new NpcAIThreadImpl().start();
		} else {
			new NpcAITimerImpl().start();
		}
	}

	/**
	 * マルチ(コア)プロセッサをサポートする為のタイマープール。 AIの實裝タイプがタイマーの場合に使用される。
	 */
	private static final TimerPool _timerPool = new TimerPool(4);

	class NpcAITimerImpl extends TimerTask implements NpcAI {
		/**
		 * 死亡處理の終了を待つタイマー
		 */
		private class DeathSyncTimer extends TimerTask {
			private void schedule(int delay) {
				_timerPool.getTimer().schedule(new DeathSyncTimer(), delay);
			}

			@Override
			public void run() {
				if (isDeathProcessing()) {
					schedule(getSleepTime());
					return;
				}
				allTargetClear();
				setAiRunning(false);
			}
		}

		@Override
		public void start() {
			setAiRunning(true);
			_timerPool.getTimer().schedule(NpcAITimerImpl.this, 0);
		}

		private void stop() {
			mobSkill.resetAllSkillUseCount();
			_timerPool.getTimer().schedule(new DeathSyncTimer(), 0); // 死亡同期を開始
		}

		// 同じインスタンスをTimerへ登錄できない為、苦肉の策。
		private void schedule(int delay) {
			_timerPool.getTimer().schedule(new NpcAITimerImpl(), delay);
		}

		@Override
		public void run() {
			try {
				if (notContinued()) {
					stop();
					return;
				}

				// XXX 同期がとても怪しげな麻痺判定
				if (0 < _paralysisTime) {
					schedule(_paralysisTime);
					_paralysisTime = 0;
					setParalyzed(false);
					return;
				} else if (isParalyzed() || isSleeped()) {
					schedule(200);
					return;
				}

				if (!AIProcess()) { // AIを續けるべきであれば、次の實行をスケジュールし、終了
					schedule(getSleepTime());
					return;
				}
				stop();
			} catch (Exception e) {
				_log.log(Level.WARNING, "NpcAI發生例外的錯誤。", e);
			}
		}

		private boolean notContinued() {
			return _destroyed || isDead() || getCurrentHp() <= 0 || getHiddenStatus() != HIDDEN_STATUS_NONE;
		}
	}

	class NpcAIThreadImpl implements Runnable, NpcAI {
		@Override
		public void start() {
			ThreadPoolManager.getInstance().execute(NpcAIThreadImpl.this);
		}

		@Override
		public void run() {
			try {
				setAiRunning(true);
				while (!_destroyed && !isDead() && getCurrentHp() > 0 && getHiddenStatus() == HIDDEN_STATUS_NONE) {
					/*
					 * if (_paralysisTime > 0) { try {
					 * Thread.sleep(_paralysisTime); } catch (Exception
					 * exception) { break; } finally { setParalyzed(false);
					 * _paralysisTime = 0; } }
					 */
					while (isParalyzed() || isSleeped()) {
						try {
							Thread.sleep(200);
						} catch (InterruptedException e) {
							setParalyzed(false);
						}
					}

					if (AIProcess()) {
						break;
					}
					try {
						// 指定時間分スレッド停止
						Thread.sleep(getSleepTime());
					} catch (Exception e) {
						break;
					}
				}
				mobSkill.resetAllSkillUseCount();
				do {
					try {
						Thread.sleep(getSleepTime());
					} catch (Exception e) {
						break;
					}
				} while (isDeathProcessing());
				allTargetClear();
				setAiRunning(false);
			} catch (Exception e) {
				_log.log(Level.WARNING, "NpcAI發生例外的錯誤。", e);
			}
		}
	}

	// ＡＩの處理 (返り值はＡＩ處理を終了するかどうか)
	private boolean AIProcess() {
		setSleepTime(300);

		checkTarget();
		if (_target == null && _master == null) {
			// 空っぽの場合はターゲットを探してみる
			// （主人がいる場合は自分でターゲットを探さない）
			searchTarget();
		}

		onItemUse();

		if (_target == null) {
			// ターゲットがいない場合
			checkTargetItem();
			if (isPickupItem() && _targetItem == null) {
				// アイテム拾う子の場合はアイテムを探してみる
				searchTargetItem();
			}

			if (_targetItem == null) {
				if (noTarget()) {
					return true;
				}
			} else {
				// onTargetItem();
				L1Inventory groundInventory = L1World.getInstance().getInventory(_targetItem.getX(),
						_targetItem.getY(), _targetItem.getMapId());
				if (groundInventory.checkItem(_targetItem.getItemId())) {
					onTargetItem();
				} else {
					_targetItemList.remove(_targetItem);
					_targetItem = null;
					setSleepTime(1000);
					return false;
				}
			}
		} else { // ターゲットがいる場合
			if (getHiddenStatus() == HIDDEN_STATUS_NONE) {
				onTarget();
			} else {
				return true;
			}
		}

		return false; // ＡＩ處理續行
	}

	// アイテム使用處理（Ｔｙｐｅによって結構違うのでオーバライドで實裝）
	public void onItemUse() {
	}

	// ターゲットを探す（Ｔｙｐｅによって結構違うのでオーバライドで實裝）
	public void searchTarget() {
	}

	// 有效なターゲットか確認及び次のターゲットを設定
	public void checkTarget() {
		if (_target == null || _target.getMapId() != getMapId() || _target.getCurrentHp() <= 0 || _target.isDead()
				|| (_target.isInvisble() && !getNpcTemplate().is_agrocoi() && !_hateList.containsKey(_target))) {
			if (_target != null) {
				tagertClear();
			}
			if (!_hateList.isEmpty()) {
				_target = _hateList.getMaxHateCharacter();
				checkTarget();
			}
		}
	}

	// ヘイトの設定
	public void setHate(L1Character cha, int hate) {
		if (cha != null && cha.getId() != getId()) {
			if (!isFirstAttack() && hate != 0) {
				// hate += 20; // ＦＡヘイト
				hate += getMaxHp() / 10; // ＦＡヘイト
				setFirstAttack(true);
			}

			_hateList.add(cha, hate);
			_dropHateList.add(cha, hate);
			_target = _hateList.getMaxHateCharacter();
			checkTarget();
		}
	}

	// リンクの設定
	public void setLink(L1Character cha) {
	}

	// 仲間意識によりアクティブになるＮＰＣの檢索（攻擊者がプレイヤーのみ有效）
	public void serchLink(L1PcInstance targetPlayer, int family) {
		List<L1Object> targetKnownObjects = targetPlayer.getKnownObjects();
		for (Object knownObject : targetKnownObjects) {
			if (knownObject instanceof L1NpcInstance) {
				L1NpcInstance npc = (L1NpcInstance) knownObject;
				if (npc.getNpcTemplate().get_agrofamily() > 0) {
					// 仲間に對してアクティブになる場合
					if (npc.getNpcTemplate().get_agrofamily() == 1) {
						// 同種族に對してのみ仲間意識
						if (npc.getNpcTemplate().get_family() == family) {
							npc.setLink(targetPlayer);
						}
					} else {
						// 全てのＮＰＣに對して仲間意識
						npc.setLink(targetPlayer);
					}
				}
				L1MobGroupInfo mobGroupInfo = getMobGroupInfo();
				if (mobGroupInfo != null) {
					if (getMobGroupId() != 0 && getMobGroupId() == npc.getMobGroupId()) { // 同じグループ
						npc.setLink(targetPlayer);
					}
				}
				// 警衛幫打動作
				if (this instanceof L1GuardInstance && knownObject instanceof L1GuardInstance) {
					L1GuardInstance guard = (L1GuardInstance) knownObject;
					if (guard.getCurrentHp() > 0) {
						guard.setLink(targetPlayer);
					}
				}

			}
		}
	}

	// ターゲットがいる場合の處理
	public void onTarget() {
		setActived(true);
		_targetItemList.clear();
		_targetItem = null;
		L1Character target = _target; // ここから先は_targetが變わると影響出るので別領域に參照確保
		if (getAtkspeed() == 0) { // 逃げるキャラ
			if (getPassispeed() > 0) { // 移動できるキャラ
				int escapeDistance = 15;
				if (hasSkillEffect(40) == true) {
					escapeDistance = 1;
				}
				if (getLocation().getTileLineDistance(target.getLocation()) > escapeDistance) { // ターゲットから逃げるの終了
					tagertClear();
				} else { // ターゲットから逃げる
					int dir = targetReverseDirection(target.getX(), target.getY());
					dir = checkObject(getX(), getY(), getMapId(), dir);
					setDirectionMove(dir);
					setSleepTime(calcSleepTime(getPassispeed(), MOVE_SPEED));
				}
			}
		} else { // 逃げないキャラ
			if (isAttackPosition(target.getX(), target.getY(), getNpcTemplate().get_ranged())) { // 攻擊可能位置
				if (mobSkill.isSkillTrigger(target)) { // トリガの條件に合うスキルがある
					if (RandomArrayList.getInt(2) == 1) { // 一定の確率で物理攻擊
						setHeading(targetDirection(target.getX(), target.getY()));
						attackTarget(target);
					} else {
						if (mobSkill.skillUse(target, true)) { // スキル使用(mobskill.sqlのTriRndに從う)
							setSleepTime(calcSleepTime(mobSkill.getSleepTime(), MAGIC_SPEED));
						} else { // スキル使用が失敗したら物理攻擊
							setHeading(targetDirection(target.getX(), target.getY()));
							attackTarget(target);
						}
					}
				} else {
					setHeading(targetDirection(target.getX(), target.getY()));
					attackTarget(target);
				}
			} else { // 攻擊不可能位置
				if (mobSkill.skillUse(target, false) && (RandomArrayList.getInt(3) == 1)) { // スキル使用(mobskill.sqlのTriRndに従わず、発動確率は33.3%。ただしサモン、強制変身は常にTriRndに従う。)
					setSleepTime(calcSleepTime(mobSkill.getSleepTime(), MAGIC_SPEED));
					return;
				}

				if (getPassispeed() > 0) {
					// 移動できるキャラ
					int distance = getLocation().getTileDistance(target.getLocation());
					if (firstFound == true && getNpcTemplate().is_teleport() && distance > 3 && distance < 15) {
						if (nearTeleport(target.getX(), target.getY()) == true) {
							firstFound = false;
							return;
						}
					}

					if (getNpcTemplate().is_teleport() && 20 > RandomArrayList.getInc(100, 1) && getCurrentMp() >= 10
							&& distance > 6 && distance < 20) { // テレポート移動
						if (nearTeleport(target.getX(), target.getY()) == true) {
							return;
						}
					}
					int dir = moveDirection(target.getX(), target.getY());
					if (dir == -1) {
						tagertClear();
					} else {
						setDirectionMove(dir);
						setSleepTime(calcSleepTime(getPassispeed(), MOVE_SPEED));
					}
				} else {
					// 移動できないキャラ（ターゲットから排除、ＰＴのときドロップチャンスがリセットされるけどまぁ自業自得）
					tagertClear();
				}
			}
		}
	}

	// 目標を指定のスキルで攻擊
	public void attackTarget(L1Character target) {
		if (target instanceof L1PcInstance) {
			L1PcInstance player = (L1PcInstance) target;
			if (player.isTeleport()) { // テレポート處理中
				return;
			}
		} else if (target instanceof L1PetInstance) {
			L1PetInstance pet = (L1PetInstance) target;
			L1Character cha = pet.getMaster();
			if (cha instanceof L1PcInstance) {
				L1PcInstance player = (L1PcInstance) cha;
				if (player.isTeleport()) { // テレポート處理中
					return;
				}
			}
		} else if (target instanceof L1SummonInstance) {
			L1SummonInstance summon = (L1SummonInstance) target;
			L1Character cha = summon.getMaster();
			if (cha instanceof L1PcInstance) {
				L1PcInstance player = (L1PcInstance) cha;
				if (player.isTeleport()) { // テレポート處理中
					return;
				}
			}
		}
		if (this instanceof L1PetInstance) {
			L1PetInstance pet = (L1PetInstance) this;
			L1Character cha = pet.getMaster();
			if (cha instanceof L1PcInstance) {
				L1PcInstance player = (L1PcInstance) cha;
				if (player.isTeleport()) { // テレポート處理中
					return;
				}
			}
		} else if (this instanceof L1SummonInstance) {
			L1SummonInstance summon = (L1SummonInstance) this;
			L1Character cha = summon.getMaster();
			if (cha instanceof L1PcInstance) {
				L1PcInstance player = (L1PcInstance) cha;
				if (player.isTeleport()) { // テレポート處理中
					return;
				}
			}
		}

		if (target instanceof L1NpcInstance) {
			L1NpcInstance npc = (L1NpcInstance) target;
			if (npc.getHiddenStatus() != HIDDEN_STATUS_NONE) { // 地中に潛っているか、飛んでいる
				allTargetClear();
				return;
			}
		}

		boolean isCounterBarrier = false;
		L1Attack attack = new L1Attack(this, target);
		if (attack.calcHit()) {
			if (target.hasSkillEffect(SKILL_COUNTER_BARRIER)) {
				L1Magic magic = new L1Magic(target, this);
				boolean isProbability = magic.calcProbabilityMagic(SKILL_COUNTER_BARRIER);
				boolean isShortDistance = attack.isShortDistance();
				if (isProbability && isShortDistance) {
					isCounterBarrier = true;
				}
			}
			if (!isCounterBarrier) {
				attack.calcDamage();
			}
		}
		if (isCounterBarrier) {
			attack.actionCounterBarrier();
			attack.commitCounterBarrier();
		} else {
			attack.action();
			attack.commit();
		}
		setSleepTime(calcSleepTime(getAtkspeed(), ATTACK_SPEED));
	}

	// ターゲットアイテムを探す
	public void searchTargetItem() {
		FastTable<L1GroundInventory> gInventorys = new FastTable<L1GroundInventory>();

		for (L1Object obj : L1World.getInstance().getVisibleObjects(this)) {
			if (obj != null && obj instanceof L1GroundInventory) {
				gInventorys.add((L1GroundInventory) obj);
			}
		}
		if (gInventorys.size() == 0) {
			return;
		}

		// 拾うアイテム(のインベントリ)をランダムで選定
		int pickupIndex = RandomArrayList.getInt(gInventorys.size());
		L1GroundInventory inventory = gInventorys.get(pickupIndex);
		for (L1ItemInstance item : inventory.getItems()) {
			if (getInventory().checkAddItem(item, item.getCount()) == L1Inventory.OK) { // 持てるならターゲットアイテムに加える
				_targetItem = item;
				_targetItemList.add(_targetItem);
			}
		}
	}

	// 飛んでいる狀態からアイテムを探し、あれば降りて拾う
	public void searchItemFromAir() {
		FastTable<L1GroundInventory> gInventorys = new FastTable<L1GroundInventory>();

		for (L1Object obj : L1World.getInstance().getVisibleObjects(this)) {
			if (obj != null && obj instanceof L1GroundInventory) {
				gInventorys.add((L1GroundInventory) obj);
			}
		}
		if (gInventorys.size() == 0) {
			return;
		}

		// 拾うアイテム(のインベントリ)をランダムで選定
		int pickupIndex = RandomArrayList.getInt(gInventorys.size());
		L1GroundInventory inventory = gInventorys.get(pickupIndex);
		for (L1ItemInstance item : inventory.getItems()) {
			if (item.getItem().getType() == 6 // potion
					|| item.getItem().getType() == 7) { // food
				if (getInventory().checkAddItem(item, item.getCount()) == L1Inventory.OK) {
					if (getHiddenStatus() == HIDDEN_STATUS_FLY) {
						setHiddenStatus(HIDDEN_STATUS_NONE);
						broadcastPacket(new S_DoActionGFX(getId(), ActionCodes.ACTION_Movedown));
						setStatus(0);
						setState(0); // add
						broadcastPacket(new S_NPCPack(this));
						onNpcAI();
						startChat(CHAT_TIMING_HIDE);
						_targetItem = item;
						_targetItemList.add(_targetItem);
					}
				}
			}
		}
	}

	public static void shuffle(L1Object[] arr) {
		int t = 0;
		for (int i = arr.length - 1; i > 0; i--) {
			t = RandomArrayList.getInt(i); // 5.14

			// 選ばれた值と交換する
			L1Object tmp = arr[i];
			arr[i] = arr[t];
			arr[t] = tmp;
		}
	}

	// 有效なターゲットアイテムか確認及び次のターゲットアイテムを設定
	public void checkTargetItem() {
		if (_targetItem == null || _targetItem.getMapId() != getMapId()
				|| getLocation().getTileDistance(_targetItem.getLocation()) > 15) {
			if (!_targetItemList.isEmpty()) {
				_targetItem = _targetItemList.get(0);
				_targetItemList.remove(0);
				checkTargetItem();
			} else {
				_targetItem = null;
			}
		}
	}

	// ターゲットアイテムがある場合の處理
	public void onTargetItem() {
		if (getLocation().getTileLineDistance(_targetItem.getLocation()) == 0) { // ピックアップ可能位置
			pickupTargetItem(_targetItem);
		} else { // ピックアップ不可能位置
			int dir = moveDirection(_targetItem.getX(), _targetItem.getY());
			if (dir == -1) { // 拾うの諦め
				_targetItemList.remove(_targetItem);
				_targetItem = null;
			} else { // ターゲットアイテムへ移動
				setDirectionMove(dir);
				setSleepTime(calcSleepTime(getPassispeed(), MOVE_SPEED));
			}
		}
	}

	// アイテムを拾う
	public void pickupTargetItem(L1ItemInstance targetItem) {
		L1Inventory groundInventory = L1World.getInstance().getInventory(targetItem.getX(), targetItem.getY(),
				targetItem.getMapId());
		L1ItemInstance item = groundInventory.tradeItem(targetItem, targetItem.getCount(), getInventory());
		turnOnOffLight();
		onGetItem(item);
		_targetItemList.remove(_targetItem);
		_targetItem = null;
		setSleepTime(1000);
	}

	// ターゲットがいない場合の處理 (返り值はＡＩ處理を終了するかどうか)
	public boolean noTarget() {
		if (_master != null && _master.getMapId() == getMapId()
				&& getLocation().getTileLineDistance(_master.getLocation()) > 2) { // 主人が同じマップにいて離れてる場合は追尾
			int dir = moveDirection(_master.getX(), _master.getY());
			if (dir != -1) {
				setDirectionMove(dir);
				setSleepTime(calcSleepTime(getPassispeed(), MOVE_SPEED));
			} else {
				return true;
			}
		} else {
			if (L1World.getInstance().getRecognizePlayer(this).size() == 0) {
				return true; // 周りにプレイヤーがいなくなったらＡＩ處理終了
			}
			// 移動できるキャラはランダムに動いておく
			if (_master == null && getPassispeed() > 0 && !isRest()) {
				// グループに屬していないorグループに屬していてリーダーの場合、ランダムに動いておく
				L1MobGroupInfo mobGroupInfo = getMobGroupInfo();
				if (mobGroupInfo == null || mobGroupInfo != null && mobGroupInfo.isLeader(this)) {
					// 移動する予定の距離を移動し終えたら、新たに距離と方向を決める
					// そうでないなら、移動する予定の距離をデクリメント
					if (_randomMoveDistance == 0) {
						if (sleeptime_PT == 0) {
							_randomMoveDirection = RandomArrayList.getInt(8);
							// 每行進 2~6 步距離，就會休息 0~7 次 等同下列說法
							// 12.5% 機率不停： 直接朝新的方向行進
							// 87.5% 機率休息： 休息方式改採NPC本身移動的間隔休息時間 1~7 次
							sleeptime_PT = RandomArrayList.getInt(8);
							_randomMoveDistance = RandomArrayList.getInc(6, 3);
						} else {
							sleeptime_PT--;
							setSleepTime(calcSleepTime(getPassispeed(), MOVE_SPEED));
						}
					} else {
						_randomMoveDistance--;
						int dir = checkObject(getX(), getY(), getMapId(), _randomMoveDirection);
						if (dir != -1) {
							setDirectionMove(dir);
						}
						setSleepTime(calcSleepTime(getPassispeed(), MOVE_SPEED));
					}
				} else { // リーダーを追尾
					L1NpcInstance leader = mobGroupInfo.getLeader();
					if (getLocation().getTileLineDistance(leader.getLocation()) > 2) {
						int dir = moveDirection(leader.getX(), leader.getY());
						if (dir == -1) {
							return true;
						} else {
							setDirectionMove(dir);
							setSleepTime(calcSleepTime(getPassispeed(), MOVE_SPEED));
						}
					}
				}
			}
		}
		return false;
	}

	public void onFinalAction(L1PcInstance pc, String s) {
	}

	// 現在のターゲットを削除
	public void tagertClear() {
		if (_target == null) {
			return;
		}
		_hateList.remove(_target);
		_target = null;
	}

	// 指定されたターゲットを削除
	public void targetRemove(L1Character target) {
		_hateList.remove(target);
		if (_target != null && _target.equals(target)) {
			_target = null;
		}
	}

	// 全てのターゲットを削除
	public void allTargetClear() {
		_hateList.clear();
		_dropHateList.clear();
		_target = null;
		_targetItemList.clear();
		_targetItem = null;
	}

	// マスターの設定
	public void setMaster(L1Character cha) {
		_master = cha;
	}

	// マスターの取得
	public L1Character getMaster() {
		return _master;
	}

	// ＡＩトリガ
	public void onNpcAI() {
	}

	// アイテム精製
	public void refineItem() {

		int[] materials = null;
		int[] counts = null;
		int[] createitem = null;
		int[] createcount = null;

		if (_npcTemplate.get_npcId() == 45032) { // ブロッブ
			// オリハルコンソードの刀身
			if (getExp() != 0 && !_inventory.checkItem(20)) {
				materials = new int[] { 40508, 40521, 40045 };
				counts = new int[] { 150, 3, 3 };
				createitem = new int[] { 20 };
				createcount = new int[] { 1 };
				if (_inventory.checkItem(materials, counts)) {
					for (int i = 0; i < materials.length; i++) {
						_inventory.consumeItem(materials[i], counts[i]);
					}
					for (int j = 0; j < createitem.length; j++) {
						_inventory.storeItem(createitem[j], createcount[j]);
					}
				}
			}
			// ロングソードの刀身
			if (getExp() != 0 && !_inventory.checkItem(19)) {
				materials = new int[] { 40494, 40521 };
				counts = new int[] { 150, 3 };
				createitem = new int[] { 19 };
				createcount = new int[] { 1 };
				if (_inventory.checkItem(materials, counts)) {
					for (int i = 0; i < materials.length; i++) {
						_inventory.consumeItem(materials[i], counts[i]);
					}
					for (int j = 0; j < createitem.length; j++) {
						_inventory.storeItem(createitem[j], createcount[j]);
					}
				}
			}
			// ショートソードの刀身
			if (getExp() != 0 && !_inventory.checkItem(3)) {
				materials = new int[] { 40494, 40521 };
				counts = new int[] { 50, 1 };
				createitem = new int[] { 3 };
				createcount = new int[] { 1 };
				if (_inventory.checkItem(materials, counts)) {
					for (int i = 0; i < materials.length; i++) {
						_inventory.consumeItem(materials[i], counts[i]);
					}
					for (int j = 0; j < createitem.length; j++) {
						_inventory.storeItem(createitem[j], createcount[j]);
					}
				}
			}
			// オリハルコンホーン
			if (getExp() != 0 && !_inventory.checkItem(100)) {
				materials = new int[] { 88, 40508, 40045 };
				counts = new int[] { 4, 80, 3 };
				createitem = new int[] { 100 };
				createcount = new int[] { 1 };
				if (_inventory.checkItem(materials, counts)) {
					for (int i = 0; i < materials.length; i++) {
						_inventory.consumeItem(materials[i], counts[i]);
					}
					for (int j = 0; j < createitem.length; j++) {
						_inventory.storeItem(createitem[j], createcount[j]);
					}
				}
			}
			// ミスリルホーン
			if (getExp() != 0 && !_inventory.checkItem(89)) {
				materials = new int[] { 88, 40494 };
				counts = new int[] { 2, 80 };
				createitem = new int[] { 89 };
				createcount = new int[] { 1 };
				if (_inventory.checkItem(materials, counts)) {
					for (int i = 0; i < materials.length; i++) {
						_inventory.consumeItem(materials[i], counts[i]);
					}
					for (int j = 0; j < createitem.length; j++) {
						L1ItemInstance item = _inventory.storeItem(createitem[j], createcount[j]);
						if (getNpcTemplate().get_digestitem() > 0) {
							setDigestItem(item);
						}
					}
				}
			}
		} else if (_npcTemplate.get_npcId() == 81069) { // ドッペルゲンガー（クエスト）
			// ドッペルゲンガーの体液
			if (getExp() != 0 && !_inventory.checkItem(40542)) {
				materials = new int[] { 40032 };
				counts = new int[] { 1 };
				createitem = new int[] { 40542 };
				createcount = new int[] { 1 };
				if (_inventory.checkItem(materials, counts)) {
					for (int i = 0; i < materials.length; i++) {
						_inventory.consumeItem(materials[i], counts[i]);
					}
					for (int j = 0; j < createitem.length; j++) {
						_inventory.storeItem(createitem[j], createcount[j]);
					}
				}
			}
		} else if (_npcTemplate.get_npcId() == 45166 // ジャックオーランタン
				|| _npcTemplate.get_npcId() == 45167) {
			// パンプキンの種
			if (getExp() != 0 && !_inventory.checkItem(40726)) {
				materials = new int[] { 40725 };
				counts = new int[] { 1 };
				createitem = new int[] { 40726 };
				createcount = new int[] { 1 };
				if (_inventory.checkItem(materials, counts)) {
					for (int i = 0; i < materials.length; i++) {
						_inventory.consumeItem(materials[i], counts[i]);
					}
					for (int j = 0; j < createitem.length; j++) {
						_inventory.storeItem(createitem[j], createcount[j]);
					}
				}
			}
		}
	}

	private boolean _aiRunning = false; // ＡＩが實行中か
	// ※ＡＩをスタートさせる時にすでに實行されてないか確認する時に使用
	private boolean _actived = false; // ＮＰＣがアクティブか
	// ※この值がfalseで_targetがいる場合、アクティブになって初行動とみなしヘイストポーション等を使わせる判定で使用
	private boolean _firstAttack = false; // ファーストアッタクされたか
	private int _sleep_time; // ＡＩを停止する時間(ms) ※行動を起こした場合に所要する時間をセット
	protected L1HateList _hateList = new L1HateList();
	protected L1HateList _dropHateList = new L1HateList();
	// ※攻擊するターゲットの判定とＰＴ時のドロップ判定で使用
	protected List<L1ItemInstance> _targetItemList = new FastTable<L1ItemInstance>(); // ダーゲットアイテム一覽
	protected L1Character _target = null; // 現在のターゲット
	protected L1ItemInstance _targetItem = null; // 現在のターゲットアイテム
	protected L1Character _master = null; // 主人orグループリーダー
	private boolean _deathProcessing = false; // 死亡處理中か
	// EXP、Drop分配中はターゲットリスト、ヘイトリストをクリアしない

	private int _paralysisTime = 0; // Paralysis RestTime

	public void setParalysisTime(int ptime) {
		_paralysisTime = ptime;
	}

	public L1HateList getHateList() {
		return _hateList;
	}

	public int getParalysisTime() {
		return _paralysisTime;
	}

	// HP自然回復
	public final void startHpRegeneration() {
		int hprInterval = getNpcTemplate().get_hprinterval();
		int hpr = getNpcTemplate().get_hpr();
		if (!_hprRunning && hprInterval > 0 && hpr > 0) {
			_hprTimer = new HprTimer(hpr);
			L1NpcRegenerationTimer.getInstance().scheduleAtFixedRate(_hprTimer, hprInterval, hprInterval);
			_hprRunning = true;
		}
	}

	public final void stopHpRegeneration() {
		if (_hprRunning) {
			_hprTimer.cancel();
			_hprRunning = false;
		}
	}

	// MP自然回復
	public final void startMpRegeneration() {
		int mprInterval = getNpcTemplate().get_mprinterval();
		int mpr = getNpcTemplate().get_mpr();
		if (!_mprRunning && mprInterval > 0 && mpr > 0) {
			_mprTimer = new MprTimer(mpr);
			L1NpcRegenerationTimer.getInstance().scheduleAtFixedRate(_mprTimer, mprInterval, mprInterval);
			_mprRunning = true;
		}
	}

	public final void stopMpRegeneration() {
		if (_mprRunning) {
			_mprTimer.cancel();
			_mprRunning = false;
		}
	}

	// ■■■■■■■■■■■■ タイマー關連 ■■■■■■■■■■

	// ＨＰ自然回復
	private boolean _hprRunning = false;

	private HprTimer _hprTimer;

	class HprTimer extends TimerTask {
		@Override
		public void run() {
			try {
				if ((!_destroyed && !isDead()) && (getCurrentHp() > 0 && getCurrentHp() < getMaxHp())) {
					setCurrentHp(getCurrentHp() + _point);
				} else {
					cancel();
					_hprRunning = false;
				}
			} catch (Exception e) {
				_log.log(Level.SEVERE, e.getLocalizedMessage(), e);
			}
		}

		public HprTimer(int point) {
			if (point < 1) {
				point = 1;
			}
			_point = point;
		}

		private final int _point;
	}

	// ＭＰ自然回復
	private boolean _mprRunning = false;

	private MprTimer _mprTimer;

	class MprTimer extends TimerTask {
		@Override
		public void run() {
			try {
				if ((!_destroyed && !isDead()) && (getCurrentHp() > 0 && getCurrentMp() < getMaxMp())) {
					setCurrentMp(getCurrentMp() + _point);
				} else {
					cancel();
					_mprRunning = false;
				}
			} catch (Exception e) {
				_log.log(Level.SEVERE, e.getLocalizedMessage(), e);
			}
		}

		public MprTimer(int point) {
			if (point < 1) {
				point = 1;
			}
			_point = point;
		}

		private final int _point;
	}

	// アイテム消化
	private Map<Integer, Integer> _digestItems;
	public boolean _digestItemRunning = false;

	class DigestItemTimer implements Runnable {
		@Override
		public void run() {
			_digestItemRunning = true;
			while (!_destroyed && _digestItems.size() > 0) {
				try {
					Thread.sleep(1000);
				} catch (Exception exception) {
					break;
				}

				Object[] keys = _digestItems.keySet().toArray();
				for (int i = 0; i < keys.length; i++) {
					Integer key = (Integer) keys[i];
					Integer digestCounter = _digestItems.get(key);
					digestCounter -= 1;
					if (digestCounter <= 0) {
						_digestItems.remove(key);
						L1ItemInstance digestItem = getInventory().getItem(key);
						if (digestItem != null) {
							getInventory().removeItem(digestItem, digestItem.getCount());
						}
					} else {
						_digestItems.put(key, digestCounter);
					}
				}
			}
			_digestItemRunning = false;
		}
	}

	// ■■■■■■■■■■■■■■■■■■■■■■■■■■■■■

	public L1NpcInstance(L1Npc template) {
		int _npcgfxId = template.get_gfxid();
		setStatus(0);
		setMoveSpeed(0);
		setDead(false);
		setStatus(0);
		setreSpawn(false);
		if (_npcgfxId == 51 || _npcgfxId == 110 || _npcgfxId == 148) {
		    setStatus(24);
		}
		if (template != null)
			setting_template(template);
	}

	// 指定のテンプレートで各種值を初期化
	public void setting_template(L1Npc template) {
		_npcTemplate = template;
		int randomlevel = 0;
		double rate = 0;
		double diff = 0;
		setName(template.get_name());
		setNameId(template.get_nameid());
		if (template.get_randomlevel() == 0) { // ランダムLv指定なし
			setLevel(template.get_level());
		} else { // ランダムLv指定あり（最小值:get_level(),最大值:get_randomlevel()）
			diff = template.get_randomlevel() - template.get_level();
			randomlevel = RandomArrayList.getInt(diff + 1);
			rate = randomlevel / diff;
			randomlevel += template.get_level();
			setLevel(randomlevel);
		}
		if (template.get_randomhp() == 0) {
			setMaxHp(template.get_hp());
			setCurrentHpDirect(template.get_hp());
		} else {
			double randomhp = rate * (template.get_randomhp() - template.get_hp());
			int hp = (int) (template.get_hp() + randomhp);
			setMaxHp(hp);
			setCurrentHpDirect(hp);
		}
		if (template.get_randommp() == 0) {
			setMaxMp(template.get_mp());
			setCurrentMpDirect(template.get_mp());
		} else {
			double randommp = rate * (template.get_randommp() - template.get_mp());
			int mp = (int) (template.get_mp() + randommp);
			setMaxMp(mp);
			setCurrentMpDirect(mp);
		}
		if (template.get_randomac() == 0) {
			setAc(template.get_ac());
		} else {
			double randomac = rate * (template.get_randomac() - template.get_ac());
			int ac = (int) (template.get_ac() + randomac);
			setAc(ac);
		}
		if (template.get_randomlevel() == 0) {
			setStr(template.get_str());
			setCon(template.get_con());
			setDex(template.get_dex());
			setInt(template.get_int());
			setWis(template.get_wis());
			setMr(template.get_mr());
		} else {
			setStr((byte) Math.min(template.get_str() + diff, 127));
			setCon((byte) Math.min(template.get_con() + diff, 127));
			setDex((byte) Math.min(template.get_dex() + diff, 127));
			setInt((byte) Math.min(template.get_int() + diff, 127));
			setWis((byte) Math.min(template.get_wis() + diff, 127));
			setMr((byte) Math.min(template.get_mr() + diff, 127));

			addHitup((int) diff * 2);
			addDmgup((int) diff * 2);
		}
		setPassispeed(template.get_passispeed());
		setAtkspeed(template.get_atkspeed());
		setAgro(template.is_agro());
		setAgrocoi(template.is_agrocoi());
		setAgrososc(template.is_agrososc());
		setTempCharGfx(template.get_gfxid());
		setGfxId(template.get_gfxid());
		if (template.get_randomexp() == 0) {
			setExp(template.get_exp());
		} else {
			int level = getLevel();
			int exp = level * level;
			exp += 1;
			setExp(exp);
		}
		if (template.get_randomlawful() == 0) {
			setLawful(template.get_lawful());
			setTempLawful(template.get_lawful());
		} else {
			double randomlawful = rate * (template.get_randomlawful() - template.get_lawful());
			int lawful = (int) (template.get_lawful() + randomlawful);
			setLawful(lawful);
			setTempLawful(lawful);
		}
		setPickupItem(template.is_picupitem());
		if (template.is_bravespeed()) {
			setBraveSpeed(1);
		} else {
			setBraveSpeed(0);
		}
		if (template.get_digestitem() > 0) {
			_digestItems = new FastMap<Integer, Integer>();
		}
		setKarma(template.getKarma());
		setLightSize(template.getLightSize());

		mobSkill = new L1MobSkillUse(this);
	}

	private int _passispeed;

	public int getPassispeed() {
		return _passispeed;
	}

	public void setPassispeed(int i) {
		_passispeed = i;
	}

	private int _atkspeed;

	public int getAtkspeed() {
		return _atkspeed;
	}

	public void setAtkspeed(int i) {
		_atkspeed = i;
	}

	private boolean _pickupItem;

	public boolean isPickupItem() {
		return _pickupItem;
	}

	public void setPickupItem(boolean flag) {
		_pickupItem = flag;
	}

	@Override
	public L1Inventory getInventory() {
		return _inventory;
	}

	public void setInventory(L1Inventory inventory) {
		_inventory = inventory;
	}

	public L1Npc getNpcTemplate() {
		return _npcTemplate;
	}

	public int getNpcId() {
		return _npcTemplate.get_npcId();
	}

	public void setPetcost(int i) {
		_petcost = i;
	}

	public int getPetcost() {
		return _petcost;
	}

	public void setSpawn(L1Spawn spawn) {
		_spawn = spawn;
	}

	public L1Spawn getSpawn() {
		return _spawn;
	}

	public void setSpawnNumber(int number) {
		_spawnNumber = number;
	}

	public int getSpawnNumber() {
		return _spawnNumber;
	}

	// オブジェクトIDをSpawnTaskに渡し再利用する
	// グループモンスターは複雜になるので再利用しない
	public void onDecay(boolean isReuseId) {
		if (isReuseId) {
			_spawn.executeSpawnTask(_spawnNumber, getId());
		}
	}

	@Override
	public void onPerceive(L1PcInstance perceivedFrom) {
		perceivedFrom.addKnownObject(this);
		perceivedFrom.sendPackets(new S_NPCPack(this));
		onNpcAI();
	}

	public void deleteMe() {
		_destroyed = true;
		if (getInventory() != null) {
			getInventory().clearItems();
		}
		allTargetClear();
		_master = null;
		L1World.getInstance().removeVisibleObject(this);
		L1World.getInstance().removeObject(this);
		List<L1PcInstance> players = L1World.getInstance().getRecognizePlayer(this);
		if (players.size() > 0) {
			S_RemoveObject s_deleteNewObject = new S_RemoveObject(this);
			for (L1PcInstance pc : players) {
				if (pc != null) {
					pc.removeKnownObject(this);
					// if(!L1Character.distancepc(user, this))
					pc.sendPackets(s_deleteNewObject);
				}
			}
		}
		removeAllKnownObjects();

		// リスパウン設定
		L1MobGroupInfo mobGroupInfo = getMobGroupInfo();
		if (mobGroupInfo == null) {
			if (isReSpawn()) {
				onDecay(true);
			}
		} else {
			if (mobGroupInfo.removeMember(this) == 0) { // グループメンバー全滅
				setMobGroupInfo(null);
				if (isReSpawn()) {
					onDecay(true);
				}
			}
		}
	}

	public void ReceiveManaDamage(L1Character attacker, int damageMp) {
	}

	public void receiveDamage(L1Character attacker, int damage) {
	}

	public void setDigestItem(L1ItemInstance item) {
		_digestItems.put(new Integer(item.getId()), new Integer(getNpcTemplate().get_digestitem()));
		if (!_digestItemRunning) {
			DigestItemTimer digestItemTimer = new DigestItemTimer();
			ThreadPoolManager.getInstance().execute(digestItemTimer);
		}
	}

	public void onGetItem(L1ItemInstance item) {
		refineItem();
		getInventory().shuffle();
		if (getNpcTemplate().get_digestitem() > 0) {
			setDigestItem(item);
		}
	}

	public void approachPlayer(L1PcInstance pc) {
		if (pc.hasSkillEffect(60) || pc.hasSkillEffect(97)) { // インビジビリティ、ブラインドハイディング中
			return;
		}
		if (getHiddenStatus() == HIDDEN_STATUS_SINK) {
			if (getCurrentHp() == getMaxHp()) {
				if (pc.getLocation().getTileLineDistance(this.getLocation()) <= 2) {
					appearOnGround(pc);
				}
			}
		} else if (getHiddenStatus() == HIDDEN_STATUS_FLY) {
			if (getCurrentHp() == getMaxHp()) {
				if (pc.getLocation().getTileLineDistance(this.getLocation()) <= 1) {
					appearOnGround(pc);
				}
			} else {
				if (getNpcTemplate().get_npcId() != 45681) { // リンドビオル以外
					searchItemFromAir();
				}
			}
		} else if (getHiddenStatus() == HIDDEN_STATUS_ICE) {
			if (getCurrentHp() < getMaxHp()) {
				appearOnGround(pc);
			}
		}
		// TODO 吉爾塔斯反擊屏障
		else if (getHiddenStatus() == HIDDEN_STATUS_COUNTER_BARRIER) { // 吉爾塔斯反擊屏障回血判斷
			if (getCurrentHp() == getMaxHp()) {
				if (pc.getLocation().getTileLineDistance(this.getLocation()) <= 2) {
					appearOnGround(pc);
				}
			}
		}
		// add end
	}

	public void appearOnGround(L1PcInstance pc) {
		if (getHiddenStatus() == HIDDEN_STATUS_SINK || getHiddenStatus() == HIDDEN_STATUS_ICE) {
			setHiddenStatus(HIDDEN_STATUS_NONE);
			broadcastPacket(new S_DoActionGFX(getId(), ActionCodes.ACTION_Appear));
			setStatus(0);
			broadcastPacket(new S_NPCPack(this));
			if (!pc.hasSkillEffect(60) && !pc.hasSkillEffect(97) // インビジビリティ、ブラインドハイディング中以外、GM以外
					&& !pc.isGm()) {
				_hateList.add(pc, 0);
				_target = pc;
			}
			onNpcAI(); // モンスターのＡＩを開始
		} else if (getHiddenStatus() == HIDDEN_STATUS_FLY) {
			setHiddenStatus(HIDDEN_STATUS_NONE);
			broadcastPacket(new S_DoActionGFX(getId(), ActionCodes.ACTION_Movedown));
			setStatus(0);
			broadcastPacket(new S_NPCPack(this));
			if (!pc.hasSkillEffect(60) && !pc.hasSkillEffect(97) // インビジビリティ、ブラインドハイディング中以外、GM以外
					&& !pc.isGm()) {
				_hateList.add(pc, 0);
				_target = pc;
			}
			onNpcAI(); // モンスターのＡＩを開始
			startChat(CHAT_TIMING_HIDE);
		}
		// TODO 吉爾塔斯反擊屏障
		else if (getHiddenStatus() == HIDDEN_STATUS_COUNTER_BARRIER) { // 吉爾塔斯解除反擊屏障
			setHiddenStatus(HIDDEN_STATUS_NONE);
			broadcastPacket(new S_DoActionGFX(getId(), ActionCodes.ACTION_AxeWalk));
			setStatus(0);
			broadcastPacket(new S_NPCPack(this));
			if (!pc.hasSkillEffect(60) && !pc.hasSkillEffect(97) // インビジビリティ、ブラインドハイディング中以外、GM以外
					&& !pc.isGm()) {
				_hateList.add(pc, 0);
				_target = pc;
				onNpcAI(); // モンスターのＡＩを開始
			}
		}
		// add end
	}

	// 指定された方向に移動させる
	public void setDirectionMove(int dir) {
		if (dir != -1) {
			int nx = MoveUtil.MoveLocX(getX(), dir);
			int ny = MoveUtil.MoveLocY(getY(), dir);
			setHeading(dir);

			getMap().setPassable(getLocation(), true);

			setX(nx);
			setY(ny);

			getMap().setPassable(getLocation(), false);

			broadcastPacket(new S_MoveCharPacket(this));

			// movement_distanceマス以上離れたらホームポイントへテレポート
			if (getMovementDistance() > 0) {
				if (this instanceof L1GuardInstance || this instanceof L1MerchantInstance
						|| this instanceof L1MonsterInstance) {
					if (getLocation().getLineDistance(new Point(getHomeX(), getHomeY())) > getMovementDistance()) {
						teleport(getHomeX(), getHomeY(), getHeading());
					}
				}
			}
			// 恨みに滿ちたソルジャーゴースト、恨みに滿ちたゴースト、恨みに滿ちたハメル將軍
			if (getNpcTemplate().get_npcId() >= 45912 && getNpcTemplate().get_npcId() <= 45916) {
				// if (getX() >= 32591 && getX() <= 32644 && getY() >= 32643 && getY() <= 32688 && getMapId() == 4) {
				if (getX() <= 32597 || getX() >= 32634 || getY() <= 32651 || getY() >= 32688 && getMapId() == 4) {
					teleport(getHomeX(), getHomeY(), getHeading());
				}
			}
		}
	}

	public int moveDirection(int x, int y) { // 目標點Ｘ 目標點Ｙ
		return moveDirection(x, y, getLocation().getLineDistance(new Point(x, y)));
	}

	// 目標までの距離に應じて最適と思われるルーチンで進む方向を返す
	public int moveDirection(int x, int y, double d) { // 目標點Ｘ 目標點Ｙ 目標までの距離
		if ((hasSkillEffect(40) == true && d >= 2D) // 持有狀態魔法"黑闇之影" + 距離2以上
				|| (d > 30D)) { // 距離距離太遠
			return -1;
		} else if (d > courceRange) { // 距離が遠い場合は單純計算
			int dir = targetDirection(x, y);
			dir = checkObject(getX(), getY(), getMapId(), dir);
			return dir;
		} else { // 目標までの最短經路を探索
			int dir = _serchCource(x, y);
			if (dir == -1) { // 目標までの經路がなっかた場合はとりあえず近づいておく
				dir = targetDirection(x, y);
				if (!isExsistCharacterBetweenTarget(dir)) {
					dir = checkObject(getX(), getY(), getMapId(), dir);
				}
			}
			return dir;
		}
	}

	private boolean isExsistCharacterBetweenTarget(int dir) {
		if (!(this instanceof L1MonsterInstance)) { // モンスター以外は對象外
			return false;
		}
		if (_target == null) { // ターゲットがいない場合
			return false;
		}

		int targetX = MoveUtil.MoveLocX(getX(), dir);
		int targetY = MoveUtil.MoveLocY(getY(), dir);

		for (L1Object object : L1World.getInstance().getVisibleObjects(this, 1)) {
			// PC, Summon, Petがいる場合
			if (object instanceof L1PcInstance || object instanceof L1SummonInstance || object instanceof L1PetInstance) {
				L1Character cha = (L1Character) object;
				// 進行方向に立ちふさがっている場合、ターゲットリストに加える
				if (cha.getX() == targetX && cha.getY() == targetY && cha.getMapId() == getMapId()) {
					if (object instanceof L1PcInstance) {
						L1PcInstance pc = (L1PcInstance) object;
						if (pc.isGhost()) { // UB觀戰中のPCは除く
							continue;
						}
					}
					_hateList.add(cha, 0);
					_target = cha;
					return true;
				}
			}
		}
		return false;
	}

	// 面向是否可能
	private static int targetFace(int heading) {
		return heading & 0x07;
	}

	// 目標の逆方向を返す
	public int targetReverseDirection(int tx, int ty) { // 目標點Ｘ 目標點Ｙ
		return targetFace(targetDirection(tx, ty) + 4);
	}

	// ■■■■■■■■■■■■■ 轉向關連 ■■■■■■■■■■■
	private static final int FIND_HEADING_TABLE[] = { 0, 1, 6, 3, 4, 5, 2, 7 };

	// 進みたい方向に障害物がないか確認、ある場合は前方斜め左右も確認後進める方向を返す
	// ※從來あった處理に、バックできない仕樣を省いて、目標の反對（左右含む）には進まないようにしたもの
	public static int checkObject(int x, int y, short m, int heading) { // 起點Ｘ 起點Ｙ
		// マップＩＤ
		// 進行方向
		L1Map map = L1WorldMap.getInstance().getMap(m);

		if (map.isPassable(x, y, heading)) {
			return heading;
		} else {
			for (int i = 1; i < 7; i++) {
				heading = targetFace(heading + FIND_HEADING_TABLE[i]);
				if (map.isPassable(x, y, heading)) {
					return heading;
				}
			}

			return -1;
		}
	}

	// 初期方向の設置
	private static final int[] firstCource = { 2, 4, 6, 0, 1, 3, 5, 7 };
	// 目標までの最短經路の方向を返す
	// ※目標を中心とした探索範圍のマップで探索
	private int _serchCource(int x, int y) { // 目標點Ｘ 目標點Ｙ
		int i;
		int locCenter = courceRange + 1;
		int diff_x = x - locCenter; // Ｘの實際のロケーションとの差
		int diff_y = y - locCenter; // Ｙの實際のロケーションとの差
		int[] locBace = { getX() - diff_x, getY() - diff_y, 0, 0 }; // Ｘ Ｙ
		// 方向
		// 初期方向
		int[] locNext = new int[4];
		int[] locCopy;
		// int[] dirFront = new int[5];
		boolean serchMap[][] = new boolean[locCenter * 2 + 1][locCenter * 2 + 1];
		FastList<int[]> queueSerch = new FastList<int[]>();

		// 探索用マップの設定
		for (int j = courceRange * 2 + 1; j > 0; j--) {
			for (i = courceRange - Math.abs(locCenter - j); i >= 0; i--) {
				serchMap[j][locCenter + i] = true;
				serchMap[j][locCenter - i] = true;
			}
		}

		for (i = 0; i < 8; i++) {
			System.arraycopy(locBace, 0, locNext, 0, 4);
			_moveLocation(locNext, firstCource[i]);
			if (locNext[0] - locCenter == 0 && locNext[1] - locCenter == 0) {
				// 最短經路が見つかった場合:鄰
				return firstCource[i];
			}
			if (serchMap[locNext[0]][locNext[1]]) {
				int tmpX = locNext[0] + diff_x - MoveUtil.MoveX(i);
				int tmpY = locNext[1] + diff_y - MoveUtil.MoveY(i);
				if (getMap().isPassable(tmpX, tmpY, i)) {// 移動經路があった場合
					locCopy = new int[4];
					System.arraycopy(locNext, 0, locCopy, 0, 4);
					locCopy[2] = firstCource[i];
					locCopy[3] = firstCource[i];
					queueSerch.add(locCopy);
				}
				serchMap[locNext[0]][locNext[1]] = false;
			}
		}
		locBace = null;

		// 最短經路を探索
		while (queueSerch.size() > 0) {
			locBace = queueSerch.removeFirst();
			// _getFront(dirFront, locBace[2]);
			for (i = 4; i >= 0; i--) {
				System.arraycopy(locBace, 0, locNext, 0, 4);
				_moveLocation(locNext, _GETFRONT[locBace[2]][i]);
				if (locNext[0] - locCenter == 0 && locNext[1] - locCenter == 0) {
					return locNext[3];
				}
				if (serchMap[locNext[0]][locNext[1]]) {
					int tmpX = locNext[0] + diff_x - MoveUtil.MoveX(i);
					int tmpY = locNext[1] + diff_y - MoveUtil.MoveY(i);
					if (getMap().isPassable(tmpX, tmpY, i)) {// 移動經路があった場合
						locCopy = new int[4];
						System.arraycopy(locNext, 0, locCopy, 0, 4);
						locCopy[2] = _GETFRONT[locBace[2]][i];
						queueSerch.add(locCopy);
					}
					serchMap[locNext[0]][locNext[1]] = false;
				}
			}
			locBace = null;
		}
		return -1; // 目標までの經路がない場合
	}

	private void _moveLocation(int[] ary, int heading) {
		ary[2] = heading;
		MoveUtil.MoveLoc(ary);
	}

	private static final int[][] _GETFRONT = {
			{ 6, 2, 0, 1, 7},
			{ 7, 2, 1, 0, 3},
			{ 4, 0, 2, 3, 1},
			{ 5, 4, 3, 2, 1},
			{ 2, 3, 4, 5, 6},
			{ 7, 6, 5, 4, 3},
			{ 4, 5, 6, 7, 0},
			{ 5, 0, 7, 6, 1}};

	// ■■■■■■■■■■■■ アイテム關連 ■■■■■■■■■■

	private void useHealPotion(int healHp, int effectId) {
		broadcastPacket(new S_SkillSound(getId(), effectId));
		if (this.hasSkillEffect(SKILL_POLLUTE_WATER)) { // ポルートウォーター中は回復量1/2倍
			healHp /= 2;
		}
		if (this instanceof L1PetInstance) {
			((L1PetInstance) this).setCurrentHp(getCurrentHp() + healHp);
		} else if (this instanceof L1SummonInstance) {
			((L1SummonInstance) this).setCurrentHp(getCurrentHp() + healHp);
		} else {
			setCurrentHpDirect(getCurrentHp() + healHp);
		}
	}

	private void useHastePotion(int time) {
		broadcastPacket(new S_SkillHaste(getId(), 1, time));
		broadcastPacket(new S_SkillSound(getId(), 191));
		setMoveSpeed(1);
		setSkillEffect(STATUS_HASTE, time * 1000);
	}

	// アイテムの使用判定及び使用
	public static final int USEITEM_HEAL = 0;
	public static final int USEITEM_HASTE = 1;
	public static int[] healPotions = { POTION_OF_GREATER_HEAL, POTION_OF_HEAL,  POTION_OF_LESSER_HEAL };
	public static int[] haestPotions = { BLESS_POTION_OF_GREATER_HASTE, POTION_OF_GREATER_HASTE, BLESS_POTION_OF_HASTE, POTION_OF_HASTE };

	public void useItem(int type, int chance) { // 使用する種類 使用する可能性(％)
		if (hasSkillEffect(71)) {
			return; // ディケイ ポーション狀態かチェック
		}

		if (RandomArrayList.getInc(100, 1) >= chance) {
			return; // 使用する可能性
		}

		if (type == USEITEM_HEAL) { // 回復系ポーション
			// 回復量の大きい順
			if (getInventory().consumeItem(POTION_OF_GREATER_HEAL, 1)) {
				useHealPotion(75, 197);
			} else if (getInventory().consumeItem(POTION_OF_HEAL, 1)) {
				useHealPotion(45, 194);
			} else if (getInventory().consumeItem(POTION_OF_LESSER_HEAL, 1)) {
				useHealPotion(15, 189);
			}
		} else if (type == USEITEM_HASTE) { // ヘイスト系ポーション
			if (hasSkillEffect(1001)) {
				return; // ヘイスト狀態チェック
			}

			// 效果の長い順
			if (getInventory().consumeItem(BLESS_POTION_OF_GREATER_HASTE, 1)) {
				useHastePotion(2100);
			} else if (getInventory().consumeItem(POTION_OF_GREATER_HASTE, 1)) {
				useHastePotion(1800);
			} else if (getInventory().consumeItem(BLESS_POTION_OF_HASTE, 1)) {
				useHastePotion(350);
			} else if (getInventory().consumeItem(POTION_OF_HASTE, 1)) {
				useHastePotion(300);
			}
		}
	}

	// ■■■■■■■■■■■■■ スキル關連(npcskillsテーブル實裝されたら消すかも) ■■■■■■■■■■■

	// 目標の鄰へテレポート
	public boolean nearTeleport(int nx, int ny) {
		int tempx = 0;
		int tempy = 0;
		for (int i = 1; i < 3; i++) {
			tempx = nx + RandomArrayList.getInc(7, -3);
			tempy = ny + RandomArrayList.getInc(7, -3);
			if (getMap().isPassable(tempx, tempy)) {
				teleport(tempx, tempy, RandomArrayList.getInt(8));
				setCurrentMp(getCurrentMp() - 10);
				return true;
			}
		}
		return false;
	}

	// 目標へテレポート
	public void teleport(int nx, int ny, int dir) {
		for (L1PcInstance pc : L1World.getInstance().getRecognizePlayer(this)) {
			pc.sendPackets(new S_SkillSound(getId(), 169));
			pc.sendPackets(new S_RemoveObject(this));
			pc.removeKnownObject(this);
		}
		setX(nx);
		setY(ny);
		setHeading(dir);
	}

	// ----------From L1Character-------------
	private String _nameId; // ● ネームＩＤ

	public String getNameId() {
		return _nameId;
	}

	public void setNameId(String s) {
		_nameId = s;
	}

	private boolean _Agro; // ● アクティブか

	public boolean isAgro() {
		return _Agro;
	}

	public void setAgro(boolean flag) {
		_Agro = flag;
	}

	private boolean _Agrocoi; // ● インビジアクティブか

	public boolean isAgrocoi() {
		return _Agrocoi;
	}

	public void setAgrocoi(boolean flag) {
		_Agrocoi = flag;
	}

	private boolean _Agrososc; // ● 變身アクティブか

	public boolean isAgrososc() {
		return _Agrososc;
	}

	public void setAgrososc(boolean flag) {
		_Agrososc = flag;
	}

	private int _homeX; // ● ホームポイントＸ（モンスターの戾る位置とかペットの警戒位置）

	public int getHomeX() {
		return _homeX;
	}

	public void setHomeX(int i) {
		_homeX = i;
	}

	private int _homeY; // ● ホームポイントＹ（モンスターの戾る位置とかペットの警戒位置）

	public int getHomeY() {
		return _homeY;
	}

	public void setHomeY(int i) {
		_homeY = i;
	}

	private boolean _reSpawn; // ● 再ポップするかどうか

	public boolean isReSpawn() {
		return _reSpawn;
	}

	public void setreSpawn(boolean flag) {
		_reSpawn = flag;
	}

	private int _lightSize; // ● ライト ０．なし １～１４．大きさ

	public int getLightSize() {
		return _lightSize;
	}

	public void setLightSize(int i) {
		_lightSize = i;
	}

	private boolean _weaponBreaked; // ● ウェポンブレイク中かどうか

	public boolean isWeaponBreaked() {
		return _weaponBreaked;
	}

	public void setWeaponBreaked(boolean flag) {
		_weaponBreaked = flag;
	}

	private int _hiddenStatus; // ● 地中に潛ったり、空を飛んでいる狀態

	public int getHiddenStatus() {
		return _hiddenStatus;
	}

	public void setHiddenStatus(int i) {
		_hiddenStatus = i;
	}

	// 行動距離
	private int _movementDistance = 0;

	public int getMovementDistance() {
		return _movementDistance;
	}

	public void setMovementDistance(int i) {
		_movementDistance = i;
	}

	// 表示用ロウフル
	private int _tempLawful = 0;

	public int getTempLawful() {
		return _tempLawful;
	}

	public void setTempLawful(int i) {
		_tempLawful = i;
	}

	protected int calcSleepTime(int sleepTime, int type) {
		switch (getMoveSpeed()) {
			case 0: // 通常
			break;
			case 1: // ヘイスト
				sleepTime *= 0.75;
			break;
			case 2: // スロー
				sleepTime *= 2;
			break;
		}
		if (getBraveSpeed() == 1) {
			sleepTime *= 0.75;
		}
		if (hasSkillEffect(SKILL_WIND_SHACKLE)) {
			if (type == ATTACK_SPEED || type == MAGIC_SPEED) {
				sleepTime *= 1.25;
			}
		}
		return sleepTime;
	}

	protected void setAiRunning(boolean aiRunning) {
		_aiRunning = aiRunning;
	}

	protected boolean isAiRunning() {
		return _aiRunning;
	}

	protected void setActived(boolean actived) {
		_actived = actived;
	}

	protected boolean isActived() {
		return _actived;
	}

	protected void setFirstAttack(boolean firstAttack) {
		_firstAttack = firstAttack;
	}

	protected boolean isFirstAttack() {
		return _firstAttack;
	}

	protected void setSleepTime(int sleep_time) {
		_sleep_time = sleep_time;
	}

	protected int getSleepTime() {
		return _sleep_time;
	}

	protected void setDeathProcessing(boolean deathProcessing) {
		_deathProcessing = deathProcessing;
	}

	protected boolean isDeathProcessing() {
		return _deathProcessing;
	}

	public int drainMana(int drain) {
		if (_drainedMana >= Config.MANA_DRAIN_LIMIT_PER_NPC) {
			return 0;
		}
		int result = Math.min(drain, getCurrentMp());
		if (_drainedMana + result > Config.MANA_DRAIN_LIMIT_PER_NPC) {
			result = Config.MANA_DRAIN_LIMIT_PER_NPC - _drainedMana;
		}
		_drainedMana += result;
		return result;
	}

	public boolean _destroyed = false; // このインスタンスが破棄されているか

	// ※破棄後に動かないよう強制的にＡＩ等のスレッド處理中止（念のため）

	// NPCが別のNPCに變わる場合の處理
	protected void transform(int transformId) {
		stopHpRegeneration();
		stopMpRegeneration();
		int transformGfxId = getNpcTemplate().getTransformGfxId();
		if (transformGfxId != 0) {
			broadcastPacket(new S_SkillSound(getId(), transformGfxId));
		}
		L1Npc npcTemplate = NpcTable.getInstance().getTemplate(transformId);
		setting_template(npcTemplate);

		broadcastPacket(new S_ChangeShape(getId(), getTempCharGfx()));
		for (L1PcInstance pc : L1World.getInstance().getRecognizePlayer(this)) {
			onPerceive(pc);
		}

	}

	public void setRest(boolean _rest) {
		this._rest = _rest;
	}

	public boolean isRest() {
		return _rest;
	}

	private boolean _isResurrect;

	public boolean isResurrect() {
		return _isResurrect;
	}

	public void setResurrect(boolean flag) {
		_isResurrect = flag;
	}

	@Override
	public synchronized void resurrect(int hp) {
		if (_destroyed) {
			return;
		}
		if (_deleteTask != null) {
			if (!_future.cancel(false)) { // キャンセルできない
				return;
			}
			_deleteTask = null;
			_future = null;
		}
		super.resurrect(hp);

		// キャンセレーションをエフェクトなしでかける
		// 本來は死亡時に行うべきだが、負荷が大きくなるため復活時に行う
		SkillUse skill = new SkillUse();
		skill.handleCommands(null, SKILL_CANCEL_MAGIC, getId(), getX(), getY(), null, 0, Base.SKILL_TYPE[1], this);
	}

	// 死んでから消えるまでの時間計測用
	private DeleteTimer _deleteTask;
	private ScheduledFuture<?> _future = null;

	protected synchronized void startDeleteTimer() {
		if (_deleteTask != null) {
			return;
		}
		_deleteTask = new DeleteTimer(getId());
		_future = ThreadPoolManager.getInstance().schedule(_deleteTask, Config.NPC_DELETION_TIME * 1000);
	}

	protected static class DeleteTimer extends TimerTask {
		private int _id;

		protected DeleteTimer(int oId) {
			_id = oId;
			if (!(L1World.getInstance().findObject(_id) instanceof L1NpcInstance)) {
				throw new IllegalArgumentException("allowed only L1NpcInstance");
			}
		}

		@Override
		public void run() {
			L1NpcInstance npc = (L1NpcInstance) L1World.getInstance().findObject(_id);
			if (npc == null || !npc.isDead() || npc._destroyed) {
				return; // 復活してるか、既に破棄濟みだったら拔け
			}
			try {
				npc.deleteMe();
			} catch (Exception e) { // 絕對例外を投げないように
				e.printStackTrace();
			}
		}
	}

	private L1MobGroupInfo _mobGroupInfo = null;

	public boolean isInMobGroup() {
		return getMobGroupInfo() != null;
	}

	public L1MobGroupInfo getMobGroupInfo() {
		return _mobGroupInfo;
	}

	public void setMobGroupInfo(L1MobGroupInfo m) {
		_mobGroupInfo = m;
	}

	private int _mobGroupId = 0;

	public int getMobGroupId() {
		return _mobGroupId;
	}

	public void setMobGroupId(int i) {
		_mobGroupId = i;
	}

	public void startChat(int chatTiming) {
		// 出現時のチャットにも關わらず死亡中、死亡時のチャットにも關わらず生存中
		if (chatTiming == CHAT_TIMING_APPEARANCE && this.isDead()) {
			return;
		}
		if (chatTiming == CHAT_TIMING_DEAD && !this.isDead()) {
			return;
		}
		if (chatTiming == CHAT_TIMING_HIDE && this.isDead()) {
			return;
		}
		if (chatTiming == CHAT_TIMING_GAME_TIME && this.isDead()) {
			return;
		}

		int npcId = this.getNpcTemplate().get_npcId();
		L1NpcChat npcChat = null;
		if (chatTiming == CHAT_TIMING_APPEARANCE) {
			npcChat = NpcChatTable.getInstance().getTemplateAppearance(npcId);
		} else if (chatTiming == CHAT_TIMING_DEAD) {
			npcChat = NpcChatTable.getInstance().getTemplateDead(npcId);
		} else if (chatTiming == CHAT_TIMING_HIDE) {
			npcChat = NpcChatTable.getInstance().getTemplateHide(npcId);
		} else if (chatTiming == CHAT_TIMING_GAME_TIME) {
			npcChat = NpcChatTable.getInstance().getTemplateGameTime(npcId);
		}
		if (npcChat == null) {
			return;
		}

		Timer timer = new Timer(true);
		L1NpcChatTimer npcChatTimer = new L1NpcChatTimer(this, npcChat);
		if (!npcChat.isRepeat()) {
			timer.schedule(npcChatTimer, npcChat.getStartDelayTime());
		} else {
			timer.scheduleAtFixedRate(npcChatTimer, npcChat.getStartDelayTime(), npcChat.getRepeatInterval());
		}
	}

	// 妖森守護神道具控制 by seroidv
	private boolean _isDropitems = false;

	public boolean isDropitems() {
		return _isDropitems;
	}

	public void setDropItems(boolean i) {
		_isDropitems = i;
	}

	private boolean _forDropitems = false;

	public boolean forDropitems() {
		return _forDropitems;
	}

	public void giveDropItems(boolean i) {
		_forDropitems = i;
	}
}
