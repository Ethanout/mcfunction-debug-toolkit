scoreboard players set @s syntax_num 42
setblock ~ ~-1 ~ chest
data modify block ~ ~-1 ~ CustomName set value '{"text":"Syntax Chest"}'
#! context name={name}; function={fname}; stack={fstack}; dimension={dimension}; anchor={anchor}
#! position={position}; rotation={rotation}
#! position-fixed={position:.2f}; rotation-fixed={rotation:.1f}
#! self-score={@s syntax_num}
#! all-scores={* syntax_num: {"{display_name} [{holder}]={score:04d}"}, ...}
#! storage={storage syntax_test:data numbers[]: {}, ...}
#! storage-fixed={storage syntax_test:data numbers[]: {value:.2f}, ...}
#! entity-pos={entity @s Pos[]: {"{entity}[{index}]={value:.3f}"}, ...}
#! block-id={block ~ ~-1 ~ id}
#! strip={storage syntax_test:data numbers[]: {}, {}, ...}
#! no-strip={storage syntax_test:data numbers[]: {}, {}, ... /no_strip}
#! literal=\{{storage syntax_test:data numbers[]: {}, ...}\}
#! nested={
#! entity @a Pos[]: {{}, ...}\n ...
#! }
#! entity-groups={
#! entity @e[type=minecraft:armor_stand,tag=syntax_probe,sort=nearest,limit=2] Pos[]: {{}, ...}\n ...
#! }
#! runtime-error={* syntax_num: only={}}
function syntax_test:child
schedule function syntax_test:scheduled 1t replace
data modify storage syntax_test:state completed set value 1b
say syntax_test_completed_after_runtime_error
