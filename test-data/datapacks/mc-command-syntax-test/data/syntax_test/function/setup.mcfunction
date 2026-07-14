scoreboard objectives add syntax_num dummy
scoreboard players set #alpha syntax_num 7
scoreboard players set #beta syntax_num -3
data modify storage syntax_test:data numbers set value [1.25d,2.5d,3.75d]
data modify storage syntax_test:data nested set value [[1,2,3],[4,5]]
data modify storage syntax_test:data text set value "hello"
data remove storage syntax_test:state completed
kill @e[type=minecraft:armor_stand,tag=syntax_probe]
summon armor_stand ~1 ~ ~ {Tags:["syntax_probe"],Invisible:1b,NoGravity:1b}
summon armor_stand ~2 ~1 ~ {Tags:["syntax_probe"],Invisible:1b,NoGravity:1b}
tellraw @a {"text":"[syntax-test] setup complete","color":"green"}
