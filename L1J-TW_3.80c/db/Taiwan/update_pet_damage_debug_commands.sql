INSERT INTO `commands` (`name`, `access_level`, `class_name`)
VALUES ('petdmg', 200, 'L1PetDmg')
ON DUPLICATE KEY UPDATE
    `access_level` = VALUES(`access_level`),
    `class_name` = VALUES(`class_name`);

INSERT INTO `commands` (`name`, `access_level`, `class_name`)
VALUES ('pethit', 200, 'L1PetHit')
ON DUPLICATE KEY UPDATE
    `access_level` = VALUES(`access_level`),
    `class_name` = VALUES(`class_name`);
