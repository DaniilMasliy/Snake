import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

 public class KeyboardListener extends KeyAdapter{

 		public void keyPressed(KeyEvent e){
 		    switch(e.getKeyCode()){
		    	case 39:	// -> Right
		    				if(ThreadsController.directionSnake != Direction.LEFT)
		    					ThreadsController.directionSnake = Direction.RIGHT;
		    			  	break;
		    	case 38:	// -> Up
							if(ThreadsController.directionSnake != Direction.DOWN)
								ThreadsController.directionSnake = Direction.UP;
		    				break;

		    	case 37: 	// -> Left
							if(ThreadsController.directionSnake != Direction.RIGHT)
								ThreadsController.directionSnake = Direction.LEFT;
		    				break;

		    	case 40:	// -> Down
							if(ThreadsController.directionSnake != Direction.UP)
								ThreadsController.directionSnake = Direction.DOWN;
		    				break;

		    	default: 	break;
 		    }
 		}

 }
