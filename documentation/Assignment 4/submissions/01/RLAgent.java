package edu.cwru.sepia.agent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edu.cwru.sepia.action.Action;
import edu.cwru.sepia.action.ActionFeedback;
import edu.cwru.sepia.action.ActionResult;
import edu.cwru.sepia.action.TargetedAction;
import edu.cwru.sepia.environment.model.history.DamageLog;
import edu.cwru.sepia.environment.model.history.DeathLog;
import edu.cwru.sepia.environment.model.history.History;
import edu.cwru.sepia.environment.model.history.History.HistoryView;
import edu.cwru.sepia.environment.model.state.State;
import edu.cwru.sepia.environment.model.state.State.StateView;
import edu.cwru.sepia.environment.model.state.Unit;
import edu.cwru.sepia.environment.model.state.Unit.UnitView;

public class RLAgent extends Agent {
	private static final long serialVersionUID = 1L;
	private static final String FOOTMAN_UNIT_NAME = "footman";
	private static final int NUM_LEARNING_EPISODES = 10;
	private static final int NUM_EVALUATING_EPISODES = 5;
	public static final double GAMMA = 0.9;
	public static final double LEARNING_RATE = .0001;
	public static final double EPSILON = .02;

	/**
	 * Convenience variable specifying enemy agent number. Use this whenever referring
	 * to the enemy agent. We will make sure it is set to the proper number when testing your code.
	 */
	public static final int ENEMY_PLAYERNUM = 1;

	/** 
	 * Use this random number generator for your epsilon exploration. When you submit we will
	 * change this seed so make sure that your agent works for more than the default seed.
	 */
	public final Random random = new Random(12345);

	public static final int NUM_FEATURES = 2;

	/**
	 * Fields for keeping track of evaluation/learning episodes and values to print
	 */
	private final int numEpisodes;
	private int currentEpisode = 0;
	private int currentPhaseEpisodeCount = 0;
	private boolean inEvaluationEpisode = true;
	private List<Double> averageRewards = new ArrayList<Double>(10);
	private double cumulativeReward = 0.0;
	
	/**
	 * Fields for figuring out rewards
	 */
	private State.StateView previousStateView;
	private boolean awardedDeathPoints;

	private List<Integer> myFootmen;
	private List<Integer> enemyFootmen;
	
	/**
	 * Fields for managing weight calculation
	 */
	public Map<Integer, List<Double>> footmenRewards = new HashMap<Integer, List<Double>>();
	public Double[] weights;

	public RLAgent(int playernum, String[] args) {
		super(playernum);

		if (args.length >= 1) {
			numEpisodes = Integer.parseInt(args[0]);
			System.out.println("Running " + numEpisodes + " episodes.");
		} else {
			numEpisodes = 10;
			System.out.println("Warning! Number of episodes not specified. Defaulting to 10 episodes.");
		}

		boolean loadWeights = false;
		if (args.length >= 2) {
			loadWeights = Boolean.parseBoolean(args[1]);
		} else {
			System.out.println("Warning! Load weights argument not specified. Defaulting to not loading.");
		}

		if (loadWeights) {
			weights = loadWeights();
		} else {
			// initialize weights to random values between -1 and 1
			weights = new Double[NUM_FEATURES];
			for (int i = 0; i < weights.length; i++) {
				weights[i] = random.nextDouble() * 2 - 1;
			}
		}
	}
	
	/**
	 * Make the lists of footmen, initialize rewards, and start the episode.
	 */
	@Override	
	public Map<Integer, Action> initialStep(State.StateView stateView, History.HistoryView historyView) {
		myFootmen = findFootmen(stateView, playernum);
		enemyFootmen = findFootmen(stateView, ENEMY_PLAYERNUM);
		initializeRewards();
		return middleStep(stateView, historyView);
	}

	private void initializeRewards() {
		for(Integer id : myFootmen){
			List<Double> rewards = new ArrayList<Double>();
			footmenRewards.put(id, rewards);
		}
	}

	/**
	 * Assigns actions and updates weights if need be.
	 *
	 * @return New actions to execute or nothing if an event has not occurred.
	 */	
	@Override
	public Map<Integer, Action> middleStep(State.StateView stateView, History.HistoryView historyView) {
		awardedDeathPoints = false;
		int previousTurnNumber = stateView.getTurnNumber() - 1;
		Map<Integer, Action> marchingOrders = new HashMap<>();
		
		if(previousTurnNumber < 0){
			return generateActions(stateView, historyView);
		}
		
		handleTurn(stateView, historyView, previousTurnNumber);
		marchingOrders = generateActions(stateView, historyView);

		previousStateView = stateView;
		return marchingOrders;
	}
	
	/**
	 * End episode and print statistics about learning rate.
	 */
	@Override
	public void terminalStep(State.StateView stateView, History.HistoryView historyView) {
		handleTurn(stateView, historyView, stateView.getTurnNumber() - 1); 
		currentPhaseEpisodeCount++;
		if(inEvaluationEpisode){
			if(currentPhaseEpisodeCount == NUM_EVALUATING_EPISODES){
				averageRewards.add(cumulativeReward/NUM_EVALUATING_EPISODES);
				cumulativeReward = 0.0;
				printTestData(averageRewards);
				currentPhaseEpisodeCount = 0;
				inEvaluationEpisode = false;
			}
		} else if(currentPhaseEpisodeCount == NUM_LEARNING_EPISODES){
			inEvaluationEpisode = true;
			currentPhaseEpisodeCount = 0;
			cumulativeReward = 0.0;
		}
		saveWeights(weights);
		currentEpisode++;
		if(currentEpisode > numEpisodes){
			System.out.println("All Episodes Completed.");
			System.exit(0);
		}
	}

	/**
	 * 
	 * If an agent died remove them from the lists and update the rewards.
	 * For the remaining agents calculate the reward and updated the weights.
	 * As we are storing the reward for each footman to update the weights
	 * calculate using the discounted reward. 
	 * 
	 * @param stateView
	 * @param historyView
	 * @param previousTurnNumber
	 */
	private void handleTurn(State.StateView stateView, History.HistoryView historyView, int previousTurnNumber) {
		Map<Integer, ActionResult> commandsIssued = historyView.getCommandFeedback(playernum, previousTurnNumber);
		List<DeathLog> deathLogs = historyView.getDeathLogs(previousTurnNumber);
		for(DeathLog deathLog : deathLogs){
			if(deathLog.getController() == ENEMY_PLAYERNUM){
				enemyFootmen.remove(((Integer) deathLog.getDeadUnitID()));
			} else {
				myFootmen.remove(((Integer) deathLog.getDeadUnitID()));
				double reward = calculateReward(stateView, historyView, deathLog.getDeadUnitID());
				cumulativeReward = cumulativeReward + reward;
				List<Double> rewards = footmenRewards.get(deathLog.getDeadUnitID());
				rewards.add(reward);
			}
		}
		
		for(Integer friendId : myFootmen){
			double reward = calculateReward(stateView, historyView, friendId);
			cumulativeReward = cumulativeReward + reward;
			int enemyId = ((TargetedAction) commandsIssued.get(friendId).getAction()).getTargetId();
			
			List<Double> rewards = footmenRewards.get(friendId);
			rewards.add(reward);
			double cumulativeDiscountedReward = getCumulativeDiscountedRewardForFootman(friendId);
			
			if(!inEvaluationEpisode){
				weights = primitiveToObjectDouble(
							updateWeights(	
								objectToPrimitiveDouble(weights), 
								calculateFeatureVector(previousStateView, historyView, friendId, enemyId),
								cumulativeDiscountedReward,
								stateView,
								historyView,
								friendId)
						);
			}
		}
	}
	
	/**
	 * Using the rewards stored at every timestep for this footman calculate
	 * the discounted reward.
	 * 
	 * @param friendId footman id of good guy
	 * @return discountedReward for entire episode for this footman
	 */
	private double getCumulativeDiscountedRewardForFootman(Integer friendId) {
		List<Double> rewards = footmenRewards.get(friendId);
		double discountedReward = 0;
		double discount = 1;
		for(int i = rewards.size() - 1; i >= 0; i--){
			discount = discount * GAMMA;
			discountedReward = discountedReward + discount*rewards.get(i);
		}
		return discountedReward;
	}
	
	/**
	 * Calculate the updated weights for this agent. 
	 * @param oldWeights Weights prior to update
	 * @param oldFeatures Features from (s,a)
	 * @param totalReward Cumulative discounted reward for this footman.
	 * @param stateView Current state of the game.
	 * @param historyView History of the game up until this point
	 * @param footmanId The footman we are updating the weights for
	 * @return The updated weight vector.
	 */
	private double[] updateWeights(double[] oldWeights, double[] oldFeatures, double totalReward, State.StateView stateView, History.HistoryView historyView, int footmanId) {
		double[] newWeights = new double[NUM_FEATURES];
		int toAttack = getArgMaxForQ(stateView, historyView, footmanId);
		double maxQValue = calcQValue(stateView, historyView, footmanId, toAttack);
		double previousQValue = calcQValueGivenFeatures(oldFeatures);
		double[] features = calculateFeatureVector(stateView, historyView, footmanId, toAttack);
		for(int i = 0; i < NUM_FEATURES; i++){
			newWeights[i] = oldWeights[i] + LEARNING_RATE * (totalReward + (GAMMA * maxQValue) - previousQValue) * features[i];
		}
		return newWeights;
	}
	
	/**
	 * 
	 * @param stateView
	 * @param historyView
	 * @return map of actions for all good guys
	 */
	private Map<Integer, Action> generateActions(State.StateView stateView, History.HistoryView historyView) {
		Map<Integer, Action> marchingOrders = new HashMap<Integer, Action>();
		for(Integer attackerId : myFootmen){
			int enemyId = selectAction(stateView, historyView, attackerId);
			Action action = Action.createCompoundAttack(attackerId, enemyId);
			marchingOrders.put(attackerId, action);
		}
		return marchingOrders;
	}

	/**
	 *
	 * @param stateView Current state of the game
	 * @param historyView The entire history of this episode
	 * @param attackerId The footman that will be attacking
	 * @return The enemy footman ID this unit should attack
	 */
	private int selectAction(State.StateView stateView, History.HistoryView historyView, int attackerId) {
		Double decider = random.nextDouble();
		if(decider < 1 - EPSILON || inEvaluationEpisode){
			return getArgMaxForQ(stateView, historyView, attackerId);
		} else {
			return enemyFootmen.get(random.nextInt(enemyFootmen.size()));
		}
	}

	/**
	 * 
	 * This is the non discounted reward the reward for each timestep is stored per footman
	 * and discounted when needed. Not discounted here.
	 * 
	 * @param stateView The current state of the game.
	 * @param historyView History of the episode up until this turn.
	 * @param footmanId The footman ID you are looking for the reward from.
	 * @return The current reward
	 */
	private double calculateReward(State.StateView stateView, History.HistoryView historyView, int footmanId) {
		double reward = -0.1;
		int previousTurnNumber = stateView.getTurnNumber() - 1;

		for(DamageLog damageLog : historyView.getDamageLogs(previousTurnNumber)) {
			if(damageLog.getAttackerController() == playernum && damageLog.getAttackerID() == footmanId){
				reward = reward + damageLog.getDamage();
			} else if(damageLog.getAttackerController() == ENEMY_PLAYERNUM && damageLog.getDefenderID() == footmanId){
				reward = reward - damageLog.getDamage();
			}
		}

		for(DeathLog deathLog : historyView.getDeathLogs(previousTurnNumber)){
			if(deathLog.getController() == ENEMY_PLAYERNUM && footmanWasAttackingDeadEnemy(footmanId, deathLog, historyView, previousTurnNumber)){
				if(!awardedDeathPoints){
					reward = reward + 100;
					awardedDeathPoints = true;
				}
			} else if(deathLog.getDeadUnitID() == footmanId) {
				reward = reward - 100;
			}
		}
		return reward;
	}

	/**
	 * 
	 * @param footmanId
	 * @param deathLog
	 * @param historyView
	 * @param previousTurnNumber
	 * @return true if on the timestep before the enemy died the footman was assigned to attack it
	 */
	private boolean footmanWasAttackingDeadEnemy(int footmanId, DeathLog deathLog, History.HistoryView historyView, int previousTurnNumber) {
		Map<Integer, ActionResult> actionResults = historyView.getCommandFeedback(playernum, previousTurnNumber);
		if(actionResults.containsKey(footmanId) && actionResults.get(footmanId).getFeedback().equals(ActionFeedback.COMPLETED)){
			TargetedAction targetedAction = (TargetedAction) actionResults.get(footmanId).getAction() ;
			return targetedAction.getTargetId() == deathLog.getDeadUnitID();
		}
		return false;
	}
	
	/** QValue/Function **/

	private int getArgMaxForQ(State.StateView stateView, History.HistoryView historyView, int attackerId) {
		int toAttackId = -1;
		double max = Double.NEGATIVE_INFINITY;
		for(Integer enemyId : enemyFootmen){
			double possible = calcQValue(stateView, historyView, attackerId, enemyId);
			if(possible > max){
				max = possible;
				toAttackId = enemyId;  
			}
		}    	
		return toAttackId;
	}
	
	/**
	 *
	 * @param stateView Current SEPIA state
	 * @param historyView Episode history up to this point in the game
	 * @param attackerId Your footman. The one doing the attacking.
	 * @param defenderId An enemy footman that your footman would be attacking
	 * @return The approximate Q-value
	 */
	private double calcQValue(State.StateView stateView, History.HistoryView historyView, int attackerId, int defenderId) {
		double[] featureValues = calculateFeatureVector(stateView, historyView, attackerId, defenderId);
		return calcQValueGivenFeatures(featureValues);
	}

	private double calcQValueGivenFeatures(double[] featureValues) { 
		double qValue = 0;
		for(int i = 0; i < NUM_FEATURES; i++){
			qValue = qValue + weights[i] * featureValues[i];
		}
		return qValue;
	}
	
	/** Feature Calculation **/

	/**
	 * 
	 * The features that ended up working the best were the constant 1, the closest enemy, and if this was the enemy
	 * the footman was previously attacking.
	 * 
	 * Other features tried but ultimately not used were:
	 * if that enemy was attacking this footman, ratio of hit points, the hp of both footmen,
	 * and number of other footmen attacking this enemy.
	 * 
	 * @param stateView Current state of the SEPIA game
	 * @param historyView History of the game up until this turn
	 * @param attackerId Your footman. The one doing the attacking.
	 * @param defenderId An enemy footman. The one you are considering attacking.
	 * @return The array of feature function outputs.
	 */
	private double[] calculateFeatureVector(State.StateView stateView, History.HistoryView historyView, int attackerId, int defenderId) {
		double[] featureValues = new double[NUM_FEATURES];

		for(int i = 0; i < NUM_FEATURES; i++){
			featureValues[i] = calculateFeatureValue(i, stateView, historyView, attackerId, defenderId);
		}

		return featureValues;
	}

	private double calculateFeatureValue(int featureId, State.StateView stateView, History.HistoryView historyView, int friendId, int enemyId){
		double result = 0;
		switch(featureId){
		case 0:
			result = 1;
			break;
		case 1:
			result = featureIsClosestEnemy(stateView, historyView, friendId, enemyId);
			break;
		case 2:
			result = featurePreviouslyAttacking(stateView, historyView, friendId, enemyId);
			break;
		case 3:
			result = featureEnemyAttackingMe(stateView, historyView, friendId, enemyId);
			break;
		case 4:
			result = featureEnemiesThatCanAttackMe(stateView, historyView, friendId);
			break;
		case 5:
			result = featureRatioOfHitPoints(stateView, friendId, enemyId);
			break;
		case 6:
			result = featureMyHp(stateView, friendId);
			break;
		case 7:
			result = featureEnemyHealth(stateView, enemyId);
			break;
		case 8:
			result = featureNumberOfGoodFootmenAttacking(stateView, historyView, enemyId);
			break;
		}
		return result;
	}
	
	private double featureIsClosestEnemy(StateView stateView, HistoryView historyView, int friendId, int enemyId) {
		int closestEnemy = getClosestEnemy(stateView, historyView, friendId);
		if(closestEnemy == enemyId ){
			return 1;
		}
		return 0;
	}
	
	private double featurePreviouslyAttacking(StateView stateView, HistoryView historyView, int friendId, int enemyId) {
		Map<Integer, Action> commandsIssued = historyView.getCommandsIssued(playernum, stateView.getTurnNumber() - 1);
		TargetedAction targetedAction = (TargetedAction) commandsIssued.get(friendId);
		if(targetedAction == null){
			return 0;
		}
		return targetedAction.getTargetId() == enemyId ? 1 : 0;
	}
	
	private double featureEnemyAttackingMe(StateView stateView, HistoryView historyView, int friendId, int enemyId) {
		int previousTurnNumber = stateView.getTurnNumber() - 1;
		if(previousTurnNumber < 0){
			return 0;
		}
		Map<Integer, Action> commandsIssued = historyView.getCommandsIssued(ENEMY_PLAYERNUM, previousTurnNumber);
		for(Action action : commandsIssued.values()){
			TargetedAction targetedAction = (TargetedAction) action;
			if(targetedAction.getTargetId() == friendId && targetedAction.getUnitId() == enemyId){
				return 1;
			}
		}
		return 0;
	}
	
	private double featureEnemiesThatCanAttackMe(StateView stateView, HistoryView historyView, int attackerId) {
		int count = 0;
		UnitView attacker = stateView.getUnit(attackerId);
		if(attacker == null){
			return count;
		}
		int attackerX = attacker.getXPosition();
		int attackerY = attacker.getYPosition();
		for(Integer enemyId : enemyFootmen){
			UnitView enemy = stateView.getUnit(enemyId);
			if(enemy != null){
				int enemyX = enemy.getXPosition();
				int enemyY = enemy.getYPosition();
				if(getDistance(attackerX, enemyX, attackerY, enemyY) < 2){
					count++;
				}
			}
		}
		return count;
	}
	
	private double featureRatioOfHitPoints(StateView stateView, int attackerId, int defenderId) {
		UnitView attacker = stateView.getUnit(attackerId);
		UnitView defender = stateView.getUnit(defenderId);
		if(attacker == null) {
			return 0;
		}
		if(defender == null){
			return 1;
		}
		return attacker.getHP()/defender.getHP();
	}
	
	private double featureMyHp(StateView stateView, int attackerId) {
		Unit.UnitView unit = stateView.getUnit(attackerId);
		if(unit == null){
			return 0;
		}
		return stateView.getUnit(attackerId).getHP();
	}
	
	private double featureEnemyHealth(StateView stateView, int enemyId) {
		UnitView enemy = stateView.getUnit(enemyId);		
		return enemy == null ? 0 : enemy.getHP();
	}
	
	private double featureNumberOfGoodFootmenAttacking(StateView stateView, HistoryView historyView, int friendId) {
		int previousTurnNumber = stateView.getTurnNumber() - 1;
		if(previousTurnNumber < 0){
			return 0;
		}
		Map<Integer, Action> commandsIssued = historyView.getCommandsIssued(playernum, previousTurnNumber);
		int count = 0;
		for(Action action : commandsIssued.values()){
			TargetedAction targetedAction = (TargetedAction) action;
			if(targetedAction.getTargetId() == friendId){
				count++;
			}
		}
		return count;
	}
	
	/** Utilities **/
	
	private int getClosestEnemy(StateView stateView, HistoryView historyView, int friendId) {
		int closestEnemyId = -1;
		double closestDistance = Double.POSITIVE_INFINITY;
		UnitView attacker = stateView.getUnit(friendId);
		if(attacker == null){
			if(enemyFootmen.isEmpty()){
				return -1;
			} else {
				return enemyFootmen.get(0);
			}
		}
		int attackerX = attacker.getXPosition();
		int attackerY = attacker.getYPosition();
		for(Integer enemyId : enemyFootmen){
			UnitView defender = stateView.getUnit(enemyId);
			if(defender != null){
				int defenderX = defender.getXPosition();
				int defenderY = defender.getYPosition();
				double distance = getDistance(attackerX, defenderX, attackerY, defenderY);
				if(distance < closestDistance){
					closestDistance = distance;
					closestEnemyId = enemyId;
				}
			}
		}
		return closestEnemyId;
	}
	

	private double getDistance(int x1, int x2, int y1, int y2){
		return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
	}
	
	private List<Integer> findFootmen(State.StateView stateView, int controllerId){
		List<Integer> footmen = new LinkedList<>();
		for (Integer unitId : stateView.getUnitIds(controllerId)) {
			Unit.UnitView unit = stateView.getUnit(unitId);
			String unitName = unit.getTemplateView().getName().toLowerCase();
			if (unitName.equals(FOOTMAN_UNIT_NAME)) {
				footmen.add(unitId);
			} else {
				System.err.println("Unknown unit type: " + unitName);
			}
		}
		return footmen;
	}
	
	public Double[] primitiveToObjectDouble(double[] array){
		Double[] result = new Double[array.length];
		for(int i = 0; i < array.length; i++){
			result[i] = array[i];
		}
		return result;
	}
	
	public double[] objectToPrimitiveDouble(Double[] array){
		double[] result = new double[array.length];
		for(int i = 0; i < array.length; i++){
			result[i] = array[i];
		}
		return result;
	}

	/**
	 * DO NOT CHANGE THIS!
	 *
	 * Prints the learning rate data described in the assignment. Do not modify this method.
	 *
	 * @param averageRewards List of cumulative average rewards from test episodes.
	 */
	public void printTestData (List<Double> averageRewards) {
		System.out.println("");
		System.out.println("Games Played      Average Cumulative Reward");
		System.out.println("-------------     -------------------------");
		for (int i = 0; i < averageRewards.size(); i++) {
			String gamesPlayed = Integer.toString(10*i);
			String averageReward = String.format("%.2f", averageRewards.get(i));

			int numSpaces = "-------------     ".length() - gamesPlayed.length();
			StringBuffer spaceBuffer = new StringBuffer(numSpaces);
			for (int j = 0; j < numSpaces; j++) {
				spaceBuffer.append(" ");
			}
			System.out.println(gamesPlayed + spaceBuffer.toString() + averageReward);
		}
		System.out.println("");
	}

	/**
	 * DO NOT CHANGE THIS!
	 *
	 * This function will take your set of weights and save them to a file. Overwriting whatever file is
	 * currently there. You will use this when training your agents. You will include the output of this function
	 * from your trained agent with your submission.
	 *
	 * Look in the agent_weights folder for the output.
	 *
	 * @param weights Array of weights
	 */
	public void saveWeights(Double[] weights) {
		File path = new File("agent_weights/weights.txt");
		// create the directories if they do not already exist
		path.getAbsoluteFile().getParentFile().mkdirs();

		try {
			// open a new file writer. Set append to false
			BufferedWriter writer = new BufferedWriter(new FileWriter(path, false));

			for (double weight : weights) {
				writer.write(String.format("%f\n", weight));
			}
			writer.flush();
			writer.close();
		} catch(IOException ex) {
			System.err.println("Failed to write weights to file. Reason: " + ex.getMessage());
		}
	}

	/**
	 * DO NOT CHANGE THIS!
	 *
	 * This function will load the weights stored at agent_weights/weights.txt. The contents of this file
	 * can be created using the saveWeights function. You will use this function if the load weights argument
	 * of the agent is set to 1.
	 *
	 * @return The array of weights
	 */
	public Double[] loadWeights() {
		File path = new File("agent_weights/weights.txt");
		if (!path.exists()) {
			System.err.println("Failed to load weights. File does not exist");
			return null;
		}

		try {
			BufferedReader reader = new BufferedReader(new FileReader(path));
			String line;
			List<Double> weights = new LinkedList<>();
			while((line = reader.readLine()) != null) {
				weights.add(Double.parseDouble(line));
			}
			reader.close();

			return weights.toArray(new Double[weights.size()]);
		} catch(IOException ex) {
			System.err.println("Failed to load weights from file. Reason: " + ex.getMessage());
		}
		return null;
	}

	@Override
	public void savePlayerData(OutputStream outputStream) {

	}

	@Override
	public void loadPlayerData(InputStream inputStream) {

	}
}
